package com.apple.game.domain.room.repository;

import com.apple.game.domain.room.entity.RoomStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RoomRedisRepository {

    private static final Duration ROOM_TTL = Duration.ofHours(6); // 방을 만든 후 6시간 동안 아무 일이 없으면 자동 소멸
    private static final Duration BOARD_TTL = Duration.ofMinutes(10);
    private static final Duration REQS_TTL = Duration.ofMinutes(5); // 처리한 requestId 보관 기간 — 한 판(120초) + 여유

    private final StringRedisTemplate redisTemplate;

    public static String roomKey(String code) { return "room:" + code; }
    public static String scoresKey(String code) { return "room:" + code + ":scores"; }
    public static String readyKey(String code) { return "room:" + code + ":ready"; }
    public static String boardKey(String code) { return "room:" + code + ":board"; }
    public static String reqsKey(String code) { return "room:" + code + ":reqs"; } // 처리 완료한 clear requestId SET
    public static String winsKey(String code) { return "room:" + code + ":wins"; } // 방 단위 승수 Hash (연전, 이탈 시 초기화)
    public static String sessionKey(String sessionId) { return "ws:session:" + sessionId; } // WS 세션 → (userId, roomCode)
    public static String sessionsKey(String code) { return "room:" + code + ":sessions"; } // userId → 현재 세션 ID (최신 것만)
    public static String discKey(String code) { return "room:" + code + ":disc"; } // userId → 이탈 nonce (유예 중인 사람)

    // Lua 스크립트 이용
    // EXISTS room:ABC123, HGET room:ABC123 status, HSET room:ABC123 guestId id
    private static final DefaultRedisScript<String> JOIN_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 'NOT_FOUND'
            end
            if redis.call('HGET', KEYS[1], 'status') == 'PLAYING' then
                return 'PLAYING'
            end
            if redis.call('HGET', KEYS[1], 'hostId') == ARGV[1] then
                return 'SELF'
            end
            if redis.call('HEXISTS', KEYS[1], 'guestId') == 1 then
                return 'FULL'
            end
            redis.call('HSET', KEYS[1], 'guestId', ARGV[1])
            redis.call('HSET', KEYS[1], 'status', ARGV[2])
            return 'OK'
            """, String.class);

    // ready 판정 원자화: [멤버 검증 + ready 등록 + 둘 다 됐는지 확인 + PLAYING 전환 + round 증가]를 한 번에 실행
    // → 동시에 ready가 와도 정확히 한 요청만 'START:{round}'를 받는다
    private static final DefaultRedisScript<String> READY_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 'NOT_FOUND'
            end
            if redis.call('HGET', KEYS[1], 'status') == 'PLAYING' then
                return 'ALREADY_PLAYING'
            end
            local hostId = redis.call('HGET', KEYS[1], 'hostId')
            local guestId = redis.call('HGET', KEYS[1], 'guestId')
            if ARGV[1] ~= hostId and ARGV[1] ~= guestId then
                return 'NOT_MEMBER'
            end
            redis.call('SADD', KEYS[2], ARGV[1])
            if guestId and redis.call('SCARD', KEYS[2]) == 2 then
                redis.call('DEL', KEYS[2])
                redis.call('HSET', KEYS[1], 'status', 'PLAYING')
                local round = redis.call('HINCRBY', KEYS[1], 'round', 1)
                return 'START:' .. round
            end
            return 'WAIT'
            """, String.class);

    // 3차: check + act를 Lua로 원자화 -> 동시 join 시 정확히 한 명만 OK를 받는다
    public String joinAtomic(String code, Long userId) {
        return redisTemplate.execute(
                JOIN_SCRIPT,
                List.of(roomKey(code)), // keys 배열 : KEYS[1] = "room:code"
                String.valueOf(userId), // ARGV 배열 : ARGV[1] = userId, ARGV[2] = "READY"
                RoomStatus.READY.name());
    }

    public String readyAtomic(String code, Long userId) {
        return redisTemplate.execute(
                READY_SCRIPT,
                List.of(roomKey(code), readyKey(code)), // KEYS[1] = 방 Hash, KEYS[2] = ready SET
                String.valueOf(userId));                // ARGV[1] = userId
    }

    // 보드를 셀 단위 Hash 필드("r:c" -> 숫자)로 저장.
    // 통짜 JSON이 아닌 이유: Step 10에서 사과 하나를 HDEL로 원자적으로 지워야 한다
    public void saveBoard(String code, int[][] board) {
        String key = boardKey(code);
        Map<String, String> fields = new HashMap<>();
        for (int r = 0; r < board.length; r++) {
            for (int c = 0; c < board[r].length; c++) {
                fields.put(r + ":" + c, String.valueOf(board[r][c]));
            }
        }
        redisTemplate.delete(key); // 이전 판 보드 잔여물 제거
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, BOARD_TTL);
    }

    // 방 생성 -> HSETNX(putIfAbsent)로 hostId 필드를 먼저 선점.
    // 같은 코드가 이미 존재하면 false -> 서비스에서 새 코드로 재시도
    public boolean createIfAbsent(String code, Long hostId) {
        String key = roomKey(code);
        Boolean claimed = redisTemplate.opsForHash().putIfAbsent(key, "hostId", String.valueOf(hostId));

        if (!Boolean.TRUE.equals(claimed)) return false;

        redisTemplate.opsForHash().put(key, "status", RoomStatus.WAITING.name());
        redisTemplate.opsForHash().put(key, "round", "0");
        redisTemplate.expire(key, ROOM_TTL);

        return true;
    }

    // HGETALL room:{code} -> 방이 없으면 빈 Map
    public Map<Object, Object> findRoom(String code) {
        return redisTemplate.opsForHash().entries(roomKey(code));
    }

    // requestId 멱등(idempotent) 처리 — 같은 requestId는 한 번만 처리한다.
    // WebSocket은 네트워크가 불안정하면 클라이언트가 같은 메시지를 재전송할 수 있고,
    // 그 재전송이 "정상적인 두 번째 clear"와 구분되지 않는다. 그래서 프론트가 요청마다 UUID를 붙이고,
    // 서버는 SADD의 반환값(새로 추가되면 1, 이미 있으면 0)으로 첫 도착만 통과시킨다.
    // SADD 자체가 원자 명령이라 같은 requestId가 동시에 두 번 와도 정확히 하나만 1을 받는다.
    public boolean markRequestOnce(String code, String requestId) {
        Long added = redisTemplate.opsForSet().add(reqsKey(code), requestId);
        redisTemplate.expire(reqsKey(code), REQS_TTL); // 판이 끝나고도 SET이 남지 않도록 TTL 갱신
        return added != null && added == 1;
    }

    // APPLES_CLEARED 브로드캐스트용 — 이번 판 점수 (userId -> score). scores Hash는 clear 성공 시 HINCRBY로 쌓인다
    public Map<Long, Integer> findScores(String code) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(scoresKey(code));
        Map<Long, Integer> scores = new LinkedHashMap<>();
        entries.forEach((k, v) -> scores.put(Long.valueOf((String) k), Integer.valueOf((String) v)));
        return scores;
    }

    // 새 판 시작 시 이전 판의 흔적 정리 — 이번 판 점수(scores)와 처리한 requestId(reqs).
    // 보드는 saveBoard()가 덮어쓰지만 scores/reqs는 지워주지 않으면 2판째 APPLES_CLEARED에 1판 점수가 섞여 나간다.
    // (승수 wins 키는 방 단위 — 여기서 건드리지 않는다)
    public void resetRoundKeys(String code) {
        redisTemplate.delete(List.of(scoresKey(code), reqsKey(code)));
    }

    // 판 종료 정산 시 승자의 승수를 1 올리고(무승부면 winnerId null → 증가 없음),
    // 두 플레이어의 승수를 돌려준다 (GAME_END의 wins). 진 사람·무승부는 0으로 채워 프론트가 키 존재를 가정하지 않게 한다.
    public Map<Long, Integer> recordWin(String code, Long winnerId, List<Long> playerIds) {
        String key = winsKey(code);
        if (winnerId != null) {
            redisTemplate.opsForHash().increment(key, String.valueOf(winnerId), 1);
        }
        redisTemplate.expire(key, ROOM_TTL);

        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        Map<Long, Integer> wins = new LinkedHashMap<>();
        for (Long id : playerIds) {
            Object v = entries.get(String.valueOf(id));
            wins.put(id, v == null ? 0 : Integer.valueOf((String) v));
        }
        return wins;
    }

    // 판 종료 후 방을 "다음 판 ready 대기" 상태로 되돌린다.
    // 보드만 정리하고 방 Hash(hostId·guestId·round)·승수(wins)는 유지 → 재-ready 시 연전.
    // scores/reqs는 다음 판 시작 시 resetRoundKeys()가 지우므로 여기서 안 지운다.
    public void finishRound(String code) {
        redisTemplate.opsForHash().put(roomKey(code), "status", RoomStatus.READY.name());
        redisTemplate.expire(roomKey(code), ROOM_TTL); // 연전 중인 방이 TTL로 소멸하지 않게 갱신
        redisTemplate.delete(boardKey(code));
    }

    // 방 삭제(혼자 있던 방에서 나가는 경우) -> 점수·ready·보드·requestId·승수 키도 같이 정리
    public void deleteRoom(String code) {
        redisTemplate.delete(List.of(roomKey(code), scoresKey(code), readyKey(code), boardKey(code), reqsKey(code), winsKey(code),
                sessionsKey(code), discKey(code)));
    }

    // 둘 중 하나가 나가고 한명만 남는 경우 -> 남는 사람을 host로 승격, Waiting으로 돌림
    // 승수(wins)도 초기화 — 연전 상대가 사라졌으므로 '그 상대와의 승수'는 의미를 잃는다 (story.md Step 11)
    public void resetToWaiting(String code, Long remainingUserId) {
        String key = roomKey(code);
        redisTemplate.opsForHash().delete(key, "guestId");
        redisTemplate.opsForHash().put(key, "hostId", String.valueOf(remainingUserId));
        redisTemplate.opsForHash().put(key, "status", RoomStatus.WAITING.name());
        redisTemplate.opsForHash().put(key, "round", "0");
        redisTemplate.delete(List.of(scoresKey(code), readyKey(code), boardKey(code), reqsKey(code), winsKey(code), discKey(code))); // 점수·ready·보드·requestId·승수·이탈표시 초기화
        redisTemplate.opsForHash().delete(key, "matchId", "startedAt");
    }

    // ---------- Step 12: 세션 매핑 · 이탈 유예 · 재접속 스냅샷 ----------

    // 판 시작 시 방 Hash에 matchId·시작 시각을 남긴다 — 재접속 스냅샷(남은 시간)과 몰수 정산이 여기서 판을 찾는다
    public void markStarted(String code, Long matchId, long startedAtEpochMs) {
        redisTemplate.opsForHash().put(roomKey(code), "matchId", String.valueOf(matchId));
        redisTemplate.opsForHash().put(roomKey(code), "startedAt", String.valueOf(startedAtEpochMs));
    }

    // WS 세션 ↔ (userId, roomCode) 양방향 매핑.
    // Redis에 두는 이유: Phase 4에서 CONNECT를 받은 인스턴스와 DISCONNECT를 감지하는 인스턴스가 다를 수 있다.
    // sessions Hash에는 유저당 '최신' 세션만 남긴다 — 새로고침으로 새 CONNECT가 먼저 오고 옛 DISCONNECT가
    // 나중에 와도, 옛 세션의 종료를 이탈로 오판하지 않기 위한 근거가 된다.
    public void bindSession(String sessionId, Long userId, String code) {
        String key = sessionKey(sessionId);
        redisTemplate.opsForHash().put(key, "userId", String.valueOf(userId));
        redisTemplate.opsForHash().put(key, "roomCode", code);
        redisTemplate.expire(key, ROOM_TTL);
        redisTemplate.opsForHash().put(sessionsKey(code), String.valueOf(userId), sessionId);
        redisTemplate.expire(sessionsKey(code), ROOM_TTL);
    }

    public Map<Object, Object> findSession(String sessionId) {
        return redisTemplate.opsForHash().entries(sessionKey(sessionId));
    }

    public void unbindSession(String sessionId) {
        redisTemplate.delete(sessionKey(sessionId));
    }

    // 이 유저의 현재(최신) 세션이 sessionId인가 — 아니면 이미 다른 세션으로 재접속한 것
    public boolean isCurrentSession(String code, Long userId, String sessionId) {
        Object cur = redisTemplate.opsForHash().get(sessionsKey(code), String.valueOf(userId));
        return sessionId.equals(cur);
    }

    // 이탈 표시. nonce를 돌려주는 이유: 유예 타이머가 실행될 때 '내가 표시한 그 이탈'이 아직 유효한지 비교한다.
    // 재접속 후 다시 끊기면 새 nonce가 쓰이므로, 옛 타이머는 nonce 불일치로 물러난다(타이머 취소 경합의 상태 기반 해결).
    public String markDisconnected(String code, Long userId) {
        String nonce = UUID.randomUUID().toString();
        redisTemplate.opsForHash().put(discKey(code), String.valueOf(userId), nonce);
        redisTemplate.expire(discKey(code), Duration.ofMinutes(5));
        return nonce;
    }

    // 재접속 — 표시 해제. 표시가 있었으면 true(RECONNECTED 브로드캐스트 대상)
    public boolean clearDisconnected(String code, Long userId) {
        Long removed = redisTemplate.opsForHash().delete(discKey(code), String.valueOf(userId));
        return removed != null && removed > 0;
    }

    public boolean isDisconnectNonceCurrent(String code, Long userId, String nonce) {
        Object cur = redisTemplate.opsForHash().get(discKey(code), String.valueOf(userId));
        return nonce.equals(cur);
    }

    // 재접속 스냅샷용 — 지워진 칸은 0 (프론트 board.setBoard가 0을 dead 셀로 그린다)
    public int[][] snapshotBoard(String code, int rows, int cols) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(boardKey(code));
        int[][] board = new int[rows][cols];
        entries.forEach((k, v) -> {
            String f = (String) k;
            int sep = f.indexOf(':');
            board[Integer.parseInt(f.substring(0, sep))][Integer.parseInt(f.substring(sep + 1))] = Integer.parseInt((String) v);
        });
        return board;
    }

    // 승수 조회(증가 없음) — 스냅샷용. 없는 키는 0
    public Map<Long, Integer> findWins(String code, List<Long> playerIds) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(winsKey(code));
        Map<Long, Integer> wins = new LinkedHashMap<>();
        for (Long id : playerIds) {
            Object v = entries.get(String.valueOf(id));
            wins.put(id, v == null ? 0 : Integer.valueOf((String) v));
        }
        return wins;
    }
}
