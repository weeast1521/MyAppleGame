package com.apple.game.domain.room.service;

import com.apple.game.domain.match.entity.GameMatch;
import com.apple.game.domain.match.repository.GameMatchRepository;
import com.apple.game.domain.match.service.MatchSettlementService;
import com.apple.game.domain.room.dto.ws.GameSocketMessage;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.solo.game.BoardGenerator;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.exception.UserErrorCode;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameStartService {

    private static final int TIME_LIMIT_SECONDS = 120;

    private final SecureRandom secureRandom = new SecureRandom();

    private final RoomRedisRepository roomRedisRepository;
    private final GameMatchRepository gameMatchRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final GameEndService gameEndService;
    private final GameTimerRegistry timerRegistry;
    private final MatchSettlementService matchSettlementService;

    public void ready(String roomCode, Long userId) {
        ready(roomCode, userId, null);
    }

    @Transactional
    public void ready(String roomCode, Long userId, String sessionId) {
        String result = roomRedisRepository.readyAtomic(roomCode, userId);

        if ("NOT_FOUND".equals(result) || "NOT_MEMBER".equals(result)) {
            log.debug("ready 무시 : roomCode={}, userId={}, result={}", roomCode, userId, result);
            return;
        }
        // 멤버 확인이 끝난 세션만 방에 묶는다 — 끊김 감지(DisconnectService)가 이 매핑으로 누구인지 안다
        if (sessionId != null) {
            roomRedisRepository.bindSession(sessionId, userId, roomCode);
        }

        // 게임 중에 온 ready = 재접속(새로고침·순단 복구) 또는 재시작으로 타이머를 잃은 잔재 판
        if ("ALREADY_PLAYING".equals(result)) {
            handleReadyWhilePlaying(roomCode, userId);
            return;
        }
        if (!result.startsWith("START:")) {
            return; // WAIT — 상대 대기
        }
        int round = Integer.parseInt(result.substring("START:".length()));

        // 시드 생성 → game_match INSERT → 같은 시드로 보드 생성 → Redis 저장
        long seed = secureRandom.nextLong();
        GameMatch match = gameMatchRepository.save(GameMatch.start(roomCode, String.valueOf(seed)));

        int[][] board = BoardGenerator.generate(seed);
        roomRedisRepository.resetRoundKeys(roomCode); // 이전 판의 scores·requestId 정리 (연전 시 점수가 이월되지 않게)
        roomRedisRepository.saveBoard(roomCode, board);
        Instant startedAt = Instant.now();
        roomRedisRepository.markStarted(roomCode, match.getId(), startedAt.toEpochMilli()); // 재접속 스냅샷·몰수 정산이 판을 찾는 근거

        // Lua가 START를 반환했다면 방 상태는 이미 PLAYING — host/guest가 모두 존재한다
        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        Long hostId = Long.valueOf((String) room.get("hostId"));
        Long guestId = Long.valueOf((String) room.get("guestId"));

        Map<Long, String> players = new LinkedHashMap<>();
        players.put(hostId, findNickname(hostId));
        players.put(guestId, findNickname(guestId));

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomCode,
                GameSocketMessage.GameStart.of(
                        match.getId(), round, players, board,
                        TIME_LIMIT_SECONDS, startedAt.toString()));

        // 판 종료 타이머 — 제한시간 + 1초 유예(마지막 순간의 clear가 도착할 시간).
        // 판정은 endByTimeUp이 다시 하므로(status·@Version) 타이머가 이르든 늦든 정합성엔 영향 없다.
        // 등록부(registry)에 두는 이유: 재접속 시 '이 인스턴스가 아는 판인가'를 has()로 판별한다.
        Long matchId = match.getId();
        timerRegistry.schedule(
                GameTimerRegistry.matchKey(matchId),
                startedAt.plusSeconds(TIME_LIMIT_SECONDS + 1),
                () -> gameEndService.endByTimeUp(matchId, roomCode));

        log.info("GAME_START: roomCode={}, matchId={}, round={}", roomCode, match.getId(), round);
    }

    /**
     * PLAYING 중의 ready 처리.
     *  - 이 인스턴스에 TIME_UP 타이머가 없는 판 = 재시작으로 타이머를 잃은 잔재 → 판 무효 후 새 판 시작
     *    (기동 시 일괄 정리 대신 지연 판정 — blue-green 전환 중 이전 색의 진행 판을 오인하지 않는다)
     *  - 정상 재접속 → 이탈 표시 해제(RECONNECTED 브로드캐스트) + 본인에게 현재 판 스냅샷
     */
    private void handleReadyWhilePlaying(String roomCode, Long userId) {
        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        String matchIdRaw = (String) room.get("matchId");
        Long matchId = matchIdRaw == null ? null : Long.valueOf(matchIdRaw);

        if (matchId == null || !timerRegistry.has(GameTimerRegistry.matchKey(matchId))) {
            log.warn("타이머 없는 PLAYING 판(재시작 잔재) — 무효 처리 후 재시작: roomCode={}, matchId={}", roomCode, matchId);
            try {
                matchSettlementService.abortActiveMatch(roomCode);
            } catch (OptimisticLockingFailureException e) {
                log.info("잔재 판 무효 경합 패배(이미 종결): roomCode={}", roomCode);
            }
            roomRedisRepository.finishRound(roomCode); // status → READY, 보드 삭제
            ready(roomCode, userId, null);             // 이제 정상 흐름(WAIT 또는 START)
            return;
        }

        if (roomRedisRepository.clearDisconnected(roomCode, userId)) {
            timerRegistry.cancel(GameTimerRegistry.graceKey(roomCode, userId)); // best-effort — 판정은 nonce가 한다
            messagingTemplate.convertAndSend("/topic/room/" + roomCode, GameSocketMessage.OpponentStatus.reconnected(userId));
            log.info("재접속(유예 내): roomCode={}, userId={}", roomCode, userId);
        }
        sendSnapshot(roomCode, userId, room, matchId);
    }

    private void sendSnapshot(String roomCode, Long userId, Map<Object, Object> room, Long matchId) {
        Long hostId = Long.valueOf((String) room.get("hostId"));
        Long guestId = Long.valueOf((String) room.get("guestId"));
        List<Long> ids = List.of(hostId, guestId);

        Map<Long, String> players = new LinkedHashMap<>();
        players.put(hostId, findNickname(hostId));
        players.put(guestId, findNickname(guestId));

        Map<Long, Integer> scores = new LinkedHashMap<>(roomRedisRepository.findScores(roomCode));
        ids.forEach(id -> scores.putIfAbsent(id, 0));

        long startedAt = Long.parseLong((String) room.getOrDefault("startedAt", "0"));
        int elapsed = (int) ((System.currentTimeMillis() - startedAt) / 1000);
        int remaining = Math.max(0, TIME_LIMIT_SECONDS - elapsed);
        int round = Integer.parseInt((String) room.getOrDefault("round", "0"));

        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId), "/queue/game",
                GameSocketMessage.GameSnapshot.of(
                        matchId, round, players,
                        roomRedisRepository.snapshotBoard(roomCode, BoardGenerator.ROWS, BoardGenerator.COLS),
                        scores, roomRedisRepository.findWins(roomCode, ids), remaining));
    }

    private String findNickname(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.NOT_FOUND));
        return user.getNickname();
    }
}
