package com.apple.game.domain.room;

import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.room.service.clear.ClearExecutor;
import com.apple.game.domain.room.service.clear.ClearOutcome;
import com.apple.game.domain.room.service.clear.ClearStrategy;
import com.apple.game.domain.solo.game.BoardGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "두 명이 같은 사과를 동시에 지워도 정확히 한 명만 성공한다"
 *
 * 레이스의 본체는 ClearExecutor.tryClear 이므로 STOMP·브로드캐스트를 떼고 Executor를 직접 겨냥한다
 * (Step 9의 RoomReadyConcurrencyTest와 같은 방식 — CountDownLatch로 두 스레드를 같은 순간에 출발).
 *
 * 세 전략을 같은 시나리오로 돌린다:
 *   - REDISSON_LOCK / LUA : 통과해야 한다 (정합성 확보)
 *   - NO_LOCK             : 실패해야 한다 (중복 득점 버그 재현) → CI를 깨지 않도록 @Disabled, 로컬에서 켜서 확인
 *
 * 한 번의 경합은 운 좋게 순서가 맞을 수도 있으므로 ROUNDS 번 반복해 "한 번이라도 둘 다 성공하면 버그"로 본다.
 */
@SpringBootTest
@ActiveProfiles("local") // 로컬 Redis(6379)가 떠 있어야 한다
class AppleClearConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(AppleClearConcurrencyTest.class);

    private static final Long HOST_ID = 920_001L;
    private static final Long GUEST_ID = 920_002L;
    private static final int ROUNDS = 20;

    // 테스트 보드: 전부 1, (0,0)=5·(0,1)=5 → 영역 (0,0)~(0,1)의 합이 정확히 10
    private static final List<String> TARGET = List.of("0:0", "0:1");

    @Autowired RoomRedisRepository roomRedisRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired List<ClearExecutor> executors;

    private String roomCode;

    @BeforeEach
    void setUp() {
        roomCode = "CLR" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        assertThat(roomRedisRepository.createIfAbsent(roomCode, HOST_ID)).isTrue();
        assertThat(roomRedisRepository.joinAtomic(roomCode, GUEST_ID)).isEqualTo("OK");
        assertThat(roomRedisRepository.readyAtomic(roomCode, HOST_ID)).isEqualTo("WAIT");
        assertThat(roomRedisRepository.readyAtomic(roomCode, GUEST_ID)).isEqualTo("START:1"); // status=PLAYING
        newBoard();
    }

    @AfterEach
    void tearDown() {
        roomRedisRepository.deleteRoom(roomCode);
    }

    // ───────────────────────── 동시성 ─────────────────────────

    @ParameterizedTest(name = "[{0}] 두 명이 같은 사과를 동시에 지워도 정확히 한 명만 성공한다")
    @EnumSource(value = ClearStrategy.class, names = {"REDISSON_LOCK", "LUA"})
    void concurrentClear_exactlyOneSuccess(ClearStrategy strategy) throws Exception {
        RaceResult result = race(executor(strategy));

        assertThat(result.doubleSuccessRounds)
                .as("둘 다 SUCCESS를 받은 라운드 수 (0이어야 정합)")
                .isZero();
        assertThat(result.totalScore)
                .as("두 명 점수 합 = 라운드마다 정확히 사과 2개")
                .isEqualTo(ROUNDS * TARGET.size());
    }

    @Test
    @DisplayName("[NO_LOCK] 1차 무방비 구현은 중복 득점 버그가 재현된다 — 로컬에서 @Disabled를 지우고 실행하면 실패한다")
    @Disabled("1차 구현의 버그 재현용. check(HMGET)와 act(HDEL) 사이의 틈으로 둘 다 성공해 항상 실패하므로 CI에서 제외")
    void concurrentClear_noLock_reproducesDoubleScore() throws Exception {
        RaceResult result = race(executor(ClearStrategy.NO_LOCK));

        // 아래 단언은 NO_LOCK에서 실패한다 — 그것이 이 테스트의 목적
        assertThat(result.doubleSuccessRounds)
                .as("둘 다 SUCCESS를 받은 라운드 수 (0이어야 정합)")
                .isZero();
        assertThat(result.totalScore).isEqualTo(ROUNDS * TARGET.size());
    }

    // ───────────────────────── 단건 판정 (최종 채택 LUA 기준) ─────────────────────────

    @Test
    @DisplayName("같은 영역을 두 번 지우면 두 번째는 ALREADY_TAKEN — 첫 번째만 점수를 얻는다")
    void secondClearOnSameArea_alreadyTaken() {
        ClearExecutor lua = executor(ClearStrategy.LUA);

        ClearOutcome first = lua.tryClear(roomCode, HOST_ID, TARGET);
        ClearOutcome second = lua.tryClear(roomCode, GUEST_ID, TARGET);

        assertThat(first.status()).isEqualTo(ClearOutcome.Status.SUCCESS);
        assertThat(first.clearedFields()).containsExactlyElementsOf(TARGET);
        assertThat(second.status()).isEqualTo(ClearOutcome.Status.ALREADY_TAKEN);

        assertThat(roomRedisRepository.findScores(roomCode)).containsExactly(Map.entry(HOST_ID, 2));
        assertThat(redisTemplate.opsForHash().hasKey(RoomRedisRepository.boardKey(roomCode), "0:0")).isFalse();
    }

    @Test
    @DisplayName("이미 지워진 칸이 섞인 영역도 남은 사과의 합이 10이면 성공한다 — cells에는 실제로 지운 칸만 담긴다")
    void areaWithEmptyCells_sumsOnlyRemaining() {
        ClearExecutor lua = executor(ClearStrategy.LUA);
        // (1,0)~(1,1)을 먼저 비운다 (테스트 보드에서 1+1=2라 그대로는 못 지우니 직접 HDEL)
        redisTemplate.opsForHash().delete(RoomRedisRepository.boardKey(roomCode), "1:0", "1:1");

        // (0,0)~(1,1): 5+5 + (비어 있음) = 10
        ClearOutcome outcome = lua.tryClear(roomCode, HOST_ID, List.of("0:0", "0:1", "1:0", "1:1"));

        assertThat(outcome.status()).isEqualTo(ClearOutcome.Status.SUCCESS);
        assertThat(outcome.clearedFields()).containsExactly("0:0", "0:1");
        assertThat(outcome.gained()).isEqualTo(2);
    }

    @Test
    @DisplayName("합이 10을 넘으면 INVALID_SUM — 아무것도 지워지지 않는다")
    void sumOverTen_invalidSum() {
        ClearOutcome outcome = executor(ClearStrategy.LUA).tryClear(roomCode, HOST_ID, List.of("0:0", "0:1", "0:2")); // 5+5+1

        assertThat(outcome.status()).isEqualTo(ClearOutcome.Status.INVALID_SUM);
        assertThat(redisTemplate.opsForHash().hasKey(RoomRedisRepository.boardKey(roomCode), "0:0")).isTrue();
        assertThat(roomRedisRepository.findScores(roomCode)).isEmpty();
    }

    @Test
    @DisplayName("방 멤버가 아니면 NOT_MEMBER, 게임 중이 아니면 NOT_PLAYING")
    void notMember_and_notPlaying() {
        ClearExecutor lua = executor(ClearStrategy.LUA);

        assertThat(lua.tryClear(roomCode, 999_999L, TARGET).status()).isEqualTo(ClearOutcome.Status.NOT_MEMBER);

        // 판 종료를 흉내 낸다 — Step 11이 구현되면 정산 로직 호출로 바뀐다
        redisTemplate.opsForHash().put(RoomRedisRepository.roomKey(roomCode), "status", "READY");
        assertThat(lua.tryClear(roomCode, HOST_ID, TARGET).status()).isEqualTo(ClearOutcome.Status.NOT_PLAYING);
        assertThat(lua.tryClear("NOROOM", HOST_ID, TARGET).status()).isEqualTo(ClearOutcome.Status.NOT_PLAYING);
    }

    @Test
    @DisplayName("같은 requestId는 한 번만 통과한다 (멱등)")
    void requestId_markedOnce() {
        String requestId = UUID.randomUUID().toString();

        assertThat(roomRedisRepository.markRequestOnce(roomCode, requestId)).isTrue();
        assertThat(roomRedisRepository.markRequestOnce(roomCode, requestId)).isFalse();
        assertThat(redisTemplate.getExpire(RoomRedisRepository.reqsKey(roomCode))).isPositive(); // TTL이 걸려 있다
    }

    // ───────────────────────── helpers ─────────────────────────

    private ClearExecutor executor(ClearStrategy strategy) {
        return executors.stream().filter(e -> e.strategy() == strategy).findFirst().orElseThrow();
    }

    private void newBoard() {
        int[][] board = new int[BoardGenerator.ROWS][BoardGenerator.COLS];
        for (int[] row : board) java.util.Arrays.fill(row, 1);
        board[0][0] = 5;
        board[0][1] = 5;
        roomRedisRepository.saveBoard(roomCode, board);
    }

    private record RaceResult(int doubleSuccessRounds, int totalScore) {}

    /** ROUNDS 번 반복: 매번 새 보드를 깔고, host·guest가 같은 영역을 동시에 지운다 */
    private RaceResult race(ClearExecutor executor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int doubleSuccess = 0;
        Map<Long, Integer> successCount = new ConcurrentHashMap<>();

        for (int round = 1; round <= ROUNDS; round++) {
            newBoard();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            List<ClearOutcome.Status> statuses = new ArrayList<>();

            for (Long userId : List.of(HOST_ID, GUEST_ID)) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();                                   // 출발선 대기
                        ClearOutcome outcome = executor.tryClear(roomCode, userId, TARGET);
                        synchronized (statuses) { statuses.add(outcome.status()); }
                        if (outcome.isSuccess()) successCount.merge(userId, 1, Integer::sum);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            start.countDown();                                           // 동시 출발!
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            long successes = statuses.stream().filter(s -> s == ClearOutcome.Status.SUCCESS).count();
            if (successes == 2) doubleSuccess++;
            log.info("[{}] round {} → {}", executor.strategy(), round, statuses);
        }
        pool.shutdown();

        int totalScore = roomRedisRepository.findScores(roomCode).values().stream().mapToInt(Integer::intValue).sum();
        log.info("[{}] 집계: 중복 득점 라운드 {}/{}, 점수 합 {} (정합이면 {}), 성공 횟수 {}",
                executor.strategy(), doubleSuccess, ROUNDS, totalScore, ROUNDS * TARGET.size(), successCount);
        return new RaceResult(doubleSuccess, totalScore);
    }
}
