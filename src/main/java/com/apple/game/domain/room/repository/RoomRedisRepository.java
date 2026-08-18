package com.apple.game.domain.room.repository;

import com.apple.game.domain.room.entity.RoomStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class RoomRedisRepository {

    private static final Duration ROOM_TTL = Duration.ofHours(6); // 방을 만든 후 6시간 동안 아무 일이 없으면 자동 소멸
    private static final Duration BOARD_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public static String roomKey(String code) { return "room:" + code; }
    public static String scoresKey(String code) { return "room:" + code + ":scores"; }
    public static String readyKey(String code) { return "room:" + code + ":ready"; }
    public static String boardKey(String code) { return "room:" + code + ":board"; }

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

    // 방 삭제(혼자 있던 방에서 나가는 경우) -> 누적 점수 키도 같이 정리
    public void deleteRoom(String code) {
        redisTemplate.delete(List.of(roomKey(code), scoresKey(code), readyKey(code), boardKey(code)));
    }

    // 둘 중 하나가 나가고 한명만 남는 경우 -> 남는 사람을 host로 승격, Waiting으로 돌림
    public void resetToWaiting(String code, Long remainingUserId) {
        String key = roomKey(code);
        redisTemplate.opsForHash().delete(key, "guestId");
        redisTemplate.opsForHash().put(key, "hostId", String.valueOf(remainingUserId));
        redisTemplate.opsForHash().put(key, "status", RoomStatus.WAITING.name());
        redisTemplate.opsForHash().put(key, "round", "0");
        redisTemplate.delete(List.of(scoresKey(code), readyKey(code), boardKey(code))); // 누적 점수·ready·보드 초기화
    }
}
