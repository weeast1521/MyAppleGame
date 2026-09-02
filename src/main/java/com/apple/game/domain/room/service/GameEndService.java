package com.apple.game.domain.room.service;

import com.apple.game.domain.match.service.MatchSettlementService;
import com.apple.game.domain.room.dto.ws.GameSocketMessage;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 판 종료의 오케스트레이션: Redis에서 판의 현재 상태를 읽고 → DB 정산(MatchSettlementService)
 * → 누적 합산 → GAME_END 브로드캐스트 → 방을 다음 판 ready 대기로 되돌린다.
 *
 * DB 정산이 트랜잭션 안, 나머지가 밖인 이유: 낙관적 락에 져서 롤백되는 경우
 * 브로드캐스트·누적 합산이 실행되면 안 된다 — "정산에 성공한 한쪽"만 뒷정리를 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameEndService {

    private final RoomRedisRepository roomRedisRepository;
    private final MatchSettlementService matchSettlementService;
    private final SimpMessagingTemplate messagingTemplate;
    private final GameTimerRegistry timerRegistry;

    /** 판 시작 시 등록된 타이머가 시간 종료에 호출한다. */
    public void endByTimeUp(Long matchId, String roomCode) {
        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        String hostRaw = (String) room.get("hostId");
        String guestRaw = (String) room.get("guestId");
        if (hostRaw == null || guestRaw == null) {
            // 판 도중 이탈로 방이 정리/축소된 경우 — leave 쪽이 match를 ABORTED 처리했다
            log.debug("TIME_UP 무시(방 상태 소멸): matchId={}, roomCode={}", matchId, roomCode);
            return;
        }
        Long hostId = Long.valueOf(hostRaw);
        Long guestId = Long.valueOf(guestRaw);

        // 이번 판 점수 (clear가 한 번도 없었으면 빈 Map → 0:0 무승부)
        Map<Long, Integer> scores = new LinkedHashMap<>(roomRedisRepository.findScores(roomCode));
        scores.putIfAbsent(hostId, 0);
        scores.putIfAbsent(guestId, 0);

        MatchSettlementService.Settlement settlement;
        try {
            settlement = matchSettlementService.settleTimeUp(matchId, hostId, guestId, scores);
        } catch (OptimisticLockingFailureException e) {
            log.info("정산 경합에서 패배 — 다른 경로가 이미 종료 처리: matchId={}", matchId);
            return;
        }
        if (settlement == null) {
            return; // 이미 FINISHED/ABORTED — 멱등
        }

        Map<Long, Integer> wins = roomRedisRepository.recordWin(roomCode, settlement.winnerUserId(), List.of(hostId, guestId));
        int round = Integer.parseInt((String) room.getOrDefault("round", "0"));

        Map<Long, String> results = new LinkedHashMap<>();
        settlement.results().forEach((userId, result) -> results.put(userId, result.name()));

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomCode,
                GameSocketMessage.GameEnd.of(matchId, round, "TIME_UP",
                        scores, wins, results, settlement.winnerUserId()));

        // 방을 READY로 되돌리고 보드 삭제 — 이 시점부터 늦게 도착한 clear는 거절된다
        roomRedisRepository.finishRound(roomCode);

        log.info("GAME_END: roomCode={}, matchId={}, round={}, winner={}",
                roomCode, matchId, round, settlement.winnerUserId());
    }

    /**
     * 이탈 유예 초과 → 몰수. 남은 사람이 FORFEIT_WIN. 판은 방 Hash의 matchId로 찾는다.
     * TIME_UP 타이머와 동시에 실행되면 @Version이 한쪽만 통과시킨다 — 몰수가 지면 조용히 물러난다
     * (그 판은 정상 TIME_UP 결과로 기록된 것이므로 올바르다).
     */
    public void endByForfeit(String roomCode, Long leaverId) {
        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        String hostRaw = (String) room.get("hostId");
        String guestRaw = (String) room.get("guestId");
        String matchIdRaw = (String) room.get("matchId");
        if (hostRaw == null || guestRaw == null || matchIdRaw == null) {
            log.debug("몰수 무시(방 상태 소멸): roomCode={}, leaver={}", roomCode, leaverId);
            return;
        }
        Long hostId = Long.valueOf(hostRaw);
        Long guestId = Long.valueOf(guestRaw);
        Long matchId = Long.valueOf(matchIdRaw);
        if (!leaverId.equals(hostId) && !leaverId.equals(guestId)) {
            return;
        }
        Long winnerId = leaverId.equals(hostId) ? guestId : hostId;

        Map<Long, Integer> scores = new LinkedHashMap<>(roomRedisRepository.findScores(roomCode));
        scores.putIfAbsent(hostId, 0);
        scores.putIfAbsent(guestId, 0);

        MatchSettlementService.Settlement settlement;
        try {
            settlement = matchSettlementService.settleForfeit(matchId, winnerId, leaverId, scores);
        } catch (OptimisticLockingFailureException e) {
            log.info("몰수 경합에서 패배 — TIME_UP 정산이 먼저 완료: matchId={}", matchId);
            return;
        }
        if (settlement == null) {
            return; // 이미 종결
        }

        timerRegistry.cancel(GameTimerRegistry.matchKey(matchId)); // TIME_UP 타이머 best-effort 취소
        Map<Long, Integer> wins = roomRedisRepository.recordWin(roomCode, winnerId, List.of(hostId, guestId));
        int round = Integer.parseInt((String) room.getOrDefault("round", "0"));
        Map<Long, String> results = new LinkedHashMap<>();
        settlement.results().forEach((userId, result) -> results.put(userId, result.name()));

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomCode,
                GameSocketMessage.GameEnd.of(matchId, round, "OPPONENT_LEFT", scores, wins, results, winnerId));
        roomRedisRepository.finishRound(roomCode);

        log.info("GAME_END(FORFEIT): roomCode={}, matchId={}, winner={}, leaver={}", roomCode, matchId, winnerId, leaverId);
    }
}
