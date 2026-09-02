package com.apple.game.domain.room;

import com.apple.game.domain.match.entity.GameMatch;
import com.apple.game.domain.match.entity.MatchResult;
import com.apple.game.domain.match.entity.MatchStatus;
import com.apple.game.domain.match.repository.GameMatchRepository;
import com.apple.game.domain.match.repository.MatchPlayerRepository;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.room.service.DisconnectService;
import com.apple.game.domain.room.service.GameEndService;
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
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 12의 동시성 본체 두 가지:
 *  1) 타이머 취소 경합 — 재접속(표시 해제)과 유예 만료가 동시에 와도 nonce가 정확히 판정하고,
 *     재접속 후 다시 끊긴 경우 옛 타이머는 물러난다.
 *  2) 몰수 vs TIME_UP — 두 종료 경로가 동시에 실행돼도 판은 정확히 한 번, 한 종류로 기록된다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DisconnectGraceRaceTest {

    private static final Logger log = LoggerFactory.getLogger(DisconnectGraceRaceTest.class);
    private static final int ROUNDS = 20;

    @Autowired RoomRedisRepository redis;
    @Autowired DisconnectService disconnectService;
    @Autowired GameEndService gameEndService;
    @Autowired GameMatchRepository gameMatchRepository;
    @Autowired MatchPlayerRepository matchPlayerRepository;
    @Autowired UserRepository userRepository;

    private User host;
    private User guest;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        host = userRepository.save(User.createLocalUser("grace-h-" + tag + "@test.com", "pw", "유예H" + tag.substring(0, 4)));
        guest = userRepository.save(User.createLocalUser("grace-g-" + tag + "@test.com", "pw", "유예G" + tag.substring(0, 4)));
    }

    @AfterEach
    void tearDown() {
        matchPlayerRepository.deleteAll(matchPlayerRepository.findAll().stream()
                .filter(mp -> mp.getUser().getId().equals(host.getId()) || mp.getUser().getId().equals(guest.getId()))
                .toList());
        userRepository.deleteAll(List.of(host, guest));
    }

    /** 방 생성 → 입장 → 양쪽 ready(START) → 판 INSERT → markStarted. 진행 중인 판 하나를 만든다. */
    private Long startPlayingRoom(String roomCode) {
        assertThat(redis.createIfAbsent(roomCode, host.getId())).isTrue();
        assertThat(redis.joinAtomic(roomCode, guest.getId())).isEqualTo("OK");
        redis.readyAtomic(roomCode, host.getId());
        assertThat(redis.readyAtomic(roomCode, guest.getId())).startsWith("START:");
        GameMatch match = gameMatchRepository.save(GameMatch.start(roomCode, "seed"));
        redis.markStarted(roomCode, match.getId(), System.currentTimeMillis());
        return match.getId();
    }

    private long playerRows(Long matchId) {
        return matchPlayerRepository.findAll().stream().filter(mp -> mp.getMatch().getId().equals(matchId)).count();
    }

    @Test
    @DisplayName("재접속 후 다시 끊기면 옛 유예 타이머는 물러나고 새 타이머만 몰수한다 (nonce)")
    void staleGraceTimerYieldsToNewer() {
        String roomCode = "GRN" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        Long matchId = startPlayingRoom(roomCode);
        try {
            String n1 = redis.markDisconnected(roomCode, guest.getId()); // 1차 끊김
            assertThat(redis.clearDisconnected(roomCode, guest.getId())).isTrue(); // 재접속
            String n2 = redis.markDisconnected(roomCode, guest.getId()); // 2차 끊김

            assertThat(redis.isDisconnectNonceCurrent(roomCode, guest.getId(), n1)).isFalse();
            assertThat(redis.isDisconnectNonceCurrent(roomCode, guest.getId(), n2)).isTrue();

            disconnectService.onGraceExpired(roomCode, guest.getId(), n1); // 옛 타이머 발화 → 무시돼야
            assertThat(gameMatchRepository.findById(matchId).orElseThrow().getStatus()).isEqualTo(MatchStatus.PLAYING);
            assertThat(redis.findRoom(roomCode)).containsEntry("status", "PLAYING");

            disconnectService.onGraceExpired(roomCode, guest.getId(), n2); // 현재 타이머 발화 → 몰수
            GameMatch after = gameMatchRepository.findById(matchId).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(MatchStatus.FINISHED);
            assertThat(matchPlayerRepository.findAll().stream()
                    .filter(mp -> mp.getMatch().getId().equals(matchId))
                    .map(mp -> mp.getResult()).toList())
                    .containsExactlyInAnyOrder(MatchResult.FORFEIT_WIN, MatchResult.FORFEIT_LOSE);
            // 이탈자 제거 → 남은 호스트 혼자 WAITING
            assertThat(redis.findRoom(roomCode)).containsEntry("status", "WAITING").doesNotContainKey("guestId");
        } finally {
            redis.deleteRoom(roomCode);
        }
    }

    @Test
    @DisplayName("재접속과 유예 만료가 동시에 와도 결과는 '몰수 1회' 또는 '몰수 없음' 중 하나다")
    void reconnectVsGraceExpiryRace() throws Exception {
        int forfeited = 0;
        int survived = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            String roomCode = "GRR" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
            Long matchId = startPlayingRoom(roomCode);
            try {
                String nonce = redis.markDisconnected(roomCode, guest.getId());
                ExecutorService pool = Executors.newFixedThreadPool(2);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                pool.submit(() -> { try { start.await(); redis.clearDisconnected(roomCode, guest.getId()); } catch (Exception ignored) {} finally { done.countDown(); } });
                pool.submit(() -> { try { start.await(); disconnectService.onGraceExpired(roomCode, guest.getId(), nonce); } catch (Exception ignored) {} finally { done.countDown(); } });
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
                pool.shutdown();

                MatchStatus status = gameMatchRepository.findById(matchId).orElseThrow().getStatus();
                long rows = playerRows(matchId);
                if (status == MatchStatus.FINISHED) {
                    assertThat(rows).as("몰수됐으면 정확히 2행").isEqualTo(2);
                    assertThat(redis.findRoom(roomCode)).containsEntry("status", "WAITING");
                    forfeited++;
                } else {
                    assertThat(rows).as("재접속이 이겼으면 정산 행 없음").isZero();
                    assertThat(status).isEqualTo(MatchStatus.PLAYING);
                    assertThat(redis.findRoom(roomCode)).containsEntry("status", "PLAYING");
                    survived++;
                }
            } finally {
                redis.deleteRoom(roomCode);
            }
        }
        log.info("재접속 vs 유예만료 {}판: 몰수 {} / 생존 {}", ROUNDS, forfeited, survived);
    }

    @Test
    @DisplayName("몰수와 TIME_UP이 동시에 실행돼도 판은 한 번, 한 종류로만 기록된다 (@Version)")
    void forfeitVsTimeUpRace() throws Exception {
        int forfeit = 0;
        int timeUp = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            String roomCode = "GRT" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
            Long matchId = startPlayingRoom(roomCode);
            try {
                ExecutorService pool = Executors.newFixedThreadPool(2);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                pool.submit(() -> { try { start.await(); gameEndService.endByForfeit(roomCode, guest.getId()); } catch (Exception ignored) {} finally { done.countDown(); } });
                pool.submit(() -> { try { start.await(); gameEndService.endByTimeUp(matchId, roomCode); } catch (Exception ignored) {} finally { done.countDown(); } });
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
                pool.shutdown();

                assertThat(gameMatchRepository.findById(matchId).orElseThrow().getStatus()).isEqualTo(MatchStatus.FINISHED);
                List<MatchResult> results = matchPlayerRepository.findAll().stream()
                        .filter(mp -> mp.getMatch().getId().equals(matchId)).map(mp -> mp.getResult()).toList();
                assertThat(results).as("정확히 2행").hasSize(2);
                Set<MatchResult> forfeitKinds = Set.of(MatchResult.FORFEIT_WIN, MatchResult.FORFEIT_LOSE);
                boolean allForfeit = results.stream().allMatch(forfeitKinds::contains);
                boolean noneForfeit = results.stream().noneMatch(forfeitKinds::contains);
                assertThat(allForfeit || noneForfeit).as("한 종류로만 기록(섞이지 않음): " + results).isTrue();
                if (allForfeit) forfeit++; else timeUp++;
            } finally {
                redis.deleteRoom(roomCode);
            }
        }
        log.info("몰수 vs TIME_UP {}판: 몰수 {} / TIME_UP {}", ROUNDS, forfeit, timeUp);
    }
}
