package com.apple.game.domain.room;

import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.room.service.clear.ClearExecutor;
import com.apple.game.domain.room.service.clear.ClearOutcome;
import com.apple.game.domain.solo.game.BoardGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세 전략의 처리량 비교 — 수치는 docs/clear_concurrency.md 에 기록한다.
 * 측정 대상은 Executor 한 번 호출의 왕복 비용이라 STOMP·브로드캐스트는 제외.
 *
 * CI에서는 돌지 않는다: CLEAR_BENCH=true ./gradlew test --tests '*ClearExecutorBenchmarkTest*' 로 수동 실행.
 *
 * 시나리오
 *   1. 순차 지연: 한 스레드가 서로 다른 사과 쌍을 N번 연속 지운다 → 요청 1건의 평균 지연 (왕복 횟수 차이가 그대로 드러난다)
 *   2. 동시 처리량: 같은 방에 8스레드가 서로 다른 사과 쌍을 동시에 지운다 → 락 경합(대기)의 비용이 드러난다
 * 보드는 전부 5라 가로 인접 두 칸이 항상 합 10 — 보드 하나에 85쌍, 다 쓰면 새 보드.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "CLEAR_BENCH", matches = "true")
class ClearExecutorBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(ClearExecutorBenchmarkTest.class);

    private static final Long HOST_ID = 930_001L;
    private static final Long GUEST_ID = 930_002L;
    private static final int PAIRS_PER_BOARD = BoardGenerator.ROWS * (BoardGenerator.COLS / 2); // 10 * 8 = 80
    private static final int SEQUENTIAL_OPS = 2_000;
    private static final int CONCURRENT_THREADS = 8;
    private static final int CONCURRENT_OPS = 2_000;

    @Autowired RoomRedisRepository roomRedisRepository;
    @Autowired List<ClearExecutor> executors;

    private String roomCode;

    @BeforeEach
    void setUp() {
        roomCode = "BEN" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        roomRedisRepository.createIfAbsent(roomCode, HOST_ID);
        roomRedisRepository.joinAtomic(roomCode, GUEST_ID);
        roomRedisRepository.readyAtomic(roomCode, HOST_ID);
        roomRedisRepository.readyAtomic(roomCode, GUEST_ID);
    }

    @AfterEach
    void tearDown() {
        roomRedisRepository.deleteRoom(roomCode);
    }

    @Test
    void sequentialLatency() {
        for (ClearExecutor executor : executors) {
            warmUp(executor);
            long begin = System.nanoTime();
            for (int i = 0; i < SEQUENTIAL_OPS; i++) {
                if (i % PAIRS_PER_BOARD == 0) newBoard();
                ClearOutcome outcome = executor.tryClear(roomCode, HOST_ID, pair(i % PAIRS_PER_BOARD));
                assertThat(outcome.isSuccess()).isTrue();
            }
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
            log.info("[순차 {}건] {} : 총 {} ms, 평균 {} µs/건, {} ops/s",
                    SEQUENTIAL_OPS, executor.strategy(), elapsedMs,
                    (System.nanoTime() - begin) / 1_000 / SEQUENTIAL_OPS,
                    SEQUENTIAL_OPS * 1000L / Math.max(1, elapsedMs));
        }
    }

    @Test
    void concurrentThroughput() throws Exception {
        for (ClearExecutor executor : executors) {
            warmUp(executor);
            // 요청마다 서로 다른 쌍을 맡는다(사과 경합 없음, 락 경합만) — 보드 하나에 80쌍이라 80건마다 새 보드를 깐다
            int boards = (int) Math.ceil((double) CONCURRENT_OPS / PAIRS_PER_BOARD);
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger timeouts = new AtomicInteger();
            long totalNanos = 0;

            for (int b = 0; b < boards; b++) {
                newBoard();
                int opsThisBoard = Math.min(PAIRS_PER_BOARD, CONCURRENT_OPS - b * PAIRS_PER_BOARD);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(opsThisBoard);
                for (int i = 0; i < opsThisBoard; i++) {
                    List<String> fields = pair(i);
                    Long userId = i % 2 == 0 ? HOST_ID : GUEST_ID;
                    pool.submit(() -> {
                        try {
                            start.await();
                            ClearOutcome outcome = executor.tryClear(roomCode, userId, fields);
                            if (outcome.isSuccess()) success.incrementAndGet();
                            else if (outcome.status() == ClearOutcome.Status.LOCK_TIMEOUT) timeouts.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                long begin = System.nanoTime();
                start.countDown();
                assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
                totalNanos += System.nanoTime() - begin;
            }
            pool.shutdown();
            long elapsedMs = totalNanos / 1_000_000;
            log.info("[동시 {}스레드 {}건] {} : 총 {} ms, {} ops/s, 성공 {}, 락 타임아웃 {}",
                    CONCURRENT_THREADS, CONCURRENT_OPS, executor.strategy(), elapsedMs,
                    CONCURRENT_OPS * 1000L / Math.max(1, elapsedMs), success.get(), timeouts.get());
        }
    }

    private void warmUp(ClearExecutor executor) {
        newBoard();
        for (int i = 0; i < 50; i++) executor.tryClear(roomCode, HOST_ID, pair(i));
    }

    private void newBoard() {
        int[][] board = new int[BoardGenerator.ROWS][BoardGenerator.COLS];
        for (int[] row : board) Arrays.fill(row, 5);
        roomRedisRepository.saveBoard(roomCode, board);
    }

    // i번째 가로 인접 쌍: (r, 2k)~(r, 2k+1)
    private List<String> pair(int i) {
        int r = i / (BoardGenerator.COLS / 2);
        int c = (i % (BoardGenerator.COLS / 2)) * 2;
        return List.of(r + ":" + c, r + ":" + (c + 1));
    }
}
