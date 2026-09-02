package com.apple.game.domain.match.service;

import com.apple.game.domain.match.entity.GameMatch;
import com.apple.game.domain.match.entity.MatchPlayer;
import com.apple.game.domain.match.entity.MatchResult;
import com.apple.game.domain.match.entity.MatchStatus;
import com.apple.game.domain.match.repository.GameMatchRepository;
import com.apple.game.domain.match.repository.MatchPlayerRepository;
import com.apple.game.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 정산의 DB 트랜잭션 부분만 담당한다 (write-behind의 "behind" 쪽).
 * Redis 읽기·브로드캐스트는 GameEndService의 몫 — 트랜잭션 경계를 DB 작업에만 좁혀서,
 * 낙관적 락 충돌(동시 정산) 시 롤백 범위가 명확하고 브로드캐스트가 이중 발송되지 않는다.
 *
 * 중복 정산 방어 3중:
 *   ① status != PLAYING이면 skip (멱등 — 이미 정산/중단된 판)
 *   ② GameMatch @Version — ①을 동시에 통과한 두 정산 중 한쪽만 커밋 성공
 *   ③ match_player (match_id, user_id) UNIQUE — DB 레벨 최후 방어선
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchSettlementService {

    private final GameMatchRepository gameMatchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final UserRepository userRepository;

    public record Settlement(Map<Long, MatchResult> results, Long winnerUserId) {}

    /**
     * TIME_UP 정산: 점수 비교로 승패 판정 → match_player 2행 INSERT → FINISHED 전이.
     * 이미 종료된 판이면 null. 낙관적 락 충돌 시 예외가 밖으로 나간다(호출부가 "졌다"로 처리).
     */
    @Transactional
    public Settlement settleTimeUp(Long matchId, Long hostId, Long guestId, Map<Long, Integer> roundScores) {
        GameMatch match = gameMatchRepository.findById(matchId).orElse(null);
        if (match == null || !match.isPlaying()) {
            return null; // 이미 정산(FINISHED)됐거나 이탈로 중단(ABORTED)된 판
        }

        int hostScore = roundScores.getOrDefault(hostId, 0);
        int guestScore = roundScores.getOrDefault(guestId, 0);

        MatchResult hostResult;
        MatchResult guestResult;
        Long winnerUserId;
        if (hostScore > guestScore) {
            hostResult = MatchResult.WIN; guestResult = MatchResult.LOSE; winnerUserId = hostId;
        } else if (hostScore < guestScore) {
            hostResult = MatchResult.LOSE; guestResult = MatchResult.WIN; winnerUserId = guestId;
        } else {
            hostResult = MatchResult.DRAW; guestResult = MatchResult.DRAW; winnerUserId = null;
        }

        match.finish(); // @Version 증가 — 동시 정산의 다른 한쪽은 커밋 시점에 실패한다

        // getReferenceById: SELECT 없이 FK만 채우는 프록시 — 정산에 유저 데이터 자체는 필요 없다
        matchPlayerRepository.save(MatchPlayer.of(match, userRepository.getReferenceById(hostId), hostScore, hostResult));
        matchPlayerRepository.save(MatchPlayer.of(match, userRepository.getReferenceById(guestId), guestScore, guestResult));

        log.info("정산 완료: matchId={}, host={}({}) vs guest={}({})",
                matchId, hostId, hostScore, guestId, guestScore);
        return new Settlement(Map.of(hostId, hostResult, guestId, guestResult), winnerUserId);
    }

    /**
     * 게임 도중 플레이어가 방을 나갈 때 — 판을 무효(ABORTED) 처리해 전적에 남기지 않는다.
     * 타이머(TIME_UP)와 동시에 실행되면 @Version이 한쪽만 통과시킨다:
     * 정산이 이기면 이쪽이 OptimisticLockingFailureException(호출부에서 무시),
     * 이쪽이 이기면 정산 쪽 settleTimeUp이 물러난다.
     */
    @Transactional
    public void abortActiveMatch(String roomCode) {
        gameMatchRepository
                .findTopByRoomCodeAndStatusOrderByIdDesc(roomCode, MatchStatus.PLAYING)
                .ifPresent(match -> {
                    match.abort();
                    log.info("판 무효 처리(이탈): matchId={}, roomCode={}", match.getId(), roomCode);
                });
    }
}
