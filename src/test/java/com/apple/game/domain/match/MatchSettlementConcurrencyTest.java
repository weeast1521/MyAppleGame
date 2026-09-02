package com.apple.game.domain.match;

import com.apple.game.domain.match.entity.GameMatch;
import com.apple.game.domain.match.entity.MatchResult;
import com.apple.game.domain.match.entity.MatchStatus;
import com.apple.game.domain.match.repository.GameMatchRepository;
import com.apple.game.domain.match.repository.MatchPlayerRepository;
import com.apple.game.domain.match.service.MatchSettlementService;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 11의 동시성 본체 검증: 타이머(TIME_UP 정산)와 이탈 처리(판 무효)가
 * 동시에 실행돼도 판의 최종 상태는 정확히 하나이고, match_player는
 * "FINISHED면 2행 / ABORTED면 0행" 외의 조합이 나오지 않는다.
 * 둘 다 PLAYING을 읽고 각자 쓰는 lost-update를 GameMatch @Version이 막는지가 관건.
 */
@SpringBootTest
@ActiveProfiles("local") // 로컬 MySQL(3307)·Redis(6379)가 떠 있어야 한다
class MatchSettlementConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(MatchSettlementConcurrencyTest.class);

    private static final int ROUNDS = 20; // 한 번의 경합은 운 좋게 비껴갈 수 있다 — 반복해서 재현 확률을 올린다

    @Autowired MatchSettlementService settlementService;
    @Autowired GameMatchRepository gameMatchRepository;
    @Autowired MatchPlayerRepository matchPlayerRepository;
    @Autowired UserRepository userRepository;

    private User host;
    private User guest;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        host = userRepository.save(User.createLocalUser("settle-h-" + tag + "@test.com", "pw", "정산H" + tag.substring(0, 4)));
        guest = userRepository.save(User.createLocalUser("settle-g-" + tag + "@test.com", "pw", "정산G" + tag.substring(0, 4)));
    }

    @AfterEach
    void tearDown() {
        matchPlayerRepository.deleteAll(matchPlayerRepository.findAll().stream()
                .filter(mp -> mp.getUser().getId().equals(host.getId()) || mp.getUser().getId().equals(guest.getId()))
                .toList());
        userRepository.deleteAll(java.util.List.of(host, guest));
    }

    @Test
    @DisplayName("타이머 정산과 이탈 무효가 동시에 실행돼도 최종 상태는 하나 — FINISHED(2행) 또는 ABORTED(0행)")
    void settleAndAbortRace() throws Exception {
        int finished = 0;
        int aborted = 0;
        AtomicInteger loserExceptions = new AtomicInteger();

        for (int round = 1; round <= ROUNDS; round++) {
            String roomCode = "STL" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
            GameMatch match = gameMatchRepository.save(GameMatch.start(roomCode, "seed-" + round));
            Long matchId = match.getId();
            Map<Long, Integer> scores = Map.of(host.getId(), 30, guest.getId(), 20);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);

            pool.submit(() -> { // 타이머 역할
                try {
                    start.await();
                    settlementService.settleTimeUp(matchId, host.getId(), guest.getId(), scores);
                } catch (OptimisticLockingFailureException e) {
                    loserExceptions.incrementAndGet(); // 경합에서 짐 — 정상 경로
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> { // 이탈 처리 역할
                try {
                    start.await();
                    settlementService.abortActiveMatch(roomCode);
                } catch (OptimisticLockingFailureException e) {
                    loserExceptions.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            GameMatch after = gameMatchRepository.findById(matchId).orElseThrow();
            long playerRows = matchPlayerRepository.findAll().stream()
                    .filter(mp -> mp.getMatch().getId().equals(matchId)).count();

            // 핵심 불변식: 종료 상태는 정확히 하나이고, 행 수는 상태와 일치한다
            assertThat(after.getStatus()).as("판은 반드시 종결 상태여야 한다").isIn(MatchStatus.FINISHED, MatchStatus.ABORTED);
            if (after.getStatus() == MatchStatus.FINISHED) {
                assertThat(playerRows).as("정산됐으면 정확히 2행").isEqualTo(2);
                finished++;
            } else {
                assertThat(playerRows).as("무효면 정산 행이 없어야 한다").isZero();
                aborted++;
            }
        }
        log.info("경합 {}판 집계: FINISHED {} / ABORTED {} / 낙관적 락 패배 {}회",
                ROUNDS, finished, aborted, loserExceptions.get());
    }

    @Test
    @DisplayName("같은 판을 두 번 정산해도 두 번째는 무시된다 (멱등)")
    void settleTwiceIsIdempotent() {
        GameMatch match = gameMatchRepository.save(GameMatch.start("STLIDEM", "seed"));
        Map<Long, Integer> scores = Map.of(host.getId(), 10, guest.getId(), 10);

        MatchSettlementService.Settlement first =
                settlementService.settleTimeUp(match.getId(), host.getId(), guest.getId(), scores);
        MatchSettlementService.Settlement second =
                settlementService.settleTimeUp(match.getId(), host.getId(), guest.getId(), scores);

        assertThat(first).isNotNull();
        assertThat(second).as("이미 FINISHED인 판은 null — 재정산 없음").isNull();
        assertThat(matchPlayerRepository.findAll().stream()
                .filter(mp -> mp.getMatch().getId().equals(match.getId())).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("점수 비교 판정 — 승/패/무승부와 winnerUserId")
    void resultJudgement() {
        GameMatch hostWins = gameMatchRepository.save(GameMatch.start("STLW1", "seed"));
        MatchSettlementService.Settlement s1 = settlementService.settleTimeUp(
                hostWins.getId(), host.getId(), guest.getId(), Map.of(host.getId(), 30, guest.getId(), 20));
        assertThat(s1.results()).containsEntry(host.getId(), MatchResult.WIN).containsEntry(guest.getId(), MatchResult.LOSE);
        assertThat(s1.winnerUserId()).isEqualTo(host.getId());

        GameMatch draw = gameMatchRepository.save(GameMatch.start("STLD1", "seed"));
        MatchSettlementService.Settlement s2 = settlementService.settleTimeUp(
                draw.getId(), host.getId(), guest.getId(), Map.of(host.getId(), 10, guest.getId(), 10));
        assertThat(s2.results()).containsEntry(host.getId(), MatchResult.DRAW).containsEntry(guest.getId(), MatchResult.DRAW);
        assertThat(s2.winnerUserId()).as("무승부면 승자 없음").isNull();
    }
}
