package com.apple.game.domain.room;

import com.apple.game.domain.room.entity.RoomStatus;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "동시에 ready가 와도 정확히 한 요청만 START를 받는다"
 * 레이스의 본체는 READY_SCRIPT(Lua)이므로 서비스가 아니라 readyAtomic을 직접 겨냥한다
 * — GameStartService까지 묶으면 브로드캐스트·DB INSERT가 딸려 와 원자성 검증이 흐려진다.
 * READY_SCRIPT는 유저 ID 문자열만 보므로 DB 유저 없이 Redis만으로 방을 차린다.
 */
@SpringBootTest
@ActiveProfiles("local") // 로컬 Redis(6379)가 떠 있어야 한다
class RoomReadyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(RoomReadyConcurrencyTest.class);

    private static final Long HOST_ID = 910_001L;
    private static final Long GUEST_ID = 910_002L;

    @Autowired RoomRedisRepository roomRedisRepository;
    @Autowired StringRedisTemplate redisTemplate;

    private String roomCode;

    @BeforeEach
    void setUp() {
        roomCode = "RDY" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        assertThat(roomRedisRepository.createIfAbsent(roomCode, HOST_ID)).isTrue();
        assertThat(roomRedisRepository.joinAtomic(roomCode, GUEST_ID)).isEqualTo("OK");
    }

    @AfterEach
    void tearDown() {
        roomRedisRepository.deleteRoom(roomCode);
    }

    @Test
    @DisplayName("두 명이 동시에 ready해도 정확히 한 요청만 START를 받는다")
    void concurrentReady_exactlyOneStart() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);   // 두 스레드를 같은 순간에 출발시킨다
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();

        for (Long userId : List.of(HOST_ID, GUEST_ID)) {
            executor.submit(() -> {
                try {
                    log.info("[user={}] 스레드 시작, 출발선 대기", userId);
                    ready.countDown();
                    start.await();                       // 출발선에서 대기
                    String result = roomRedisRepository.readyAtomic(roomCode, userId);
                    log.info("[user={}] ready 결과: {}", userId, result);
                    results.add(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        log.info("두 스레드 준비 완료, 출발 신호 발사");
        start.countDown();                               // 동시 출발!
        done.await(10, TimeUnit.SECONDS);
        log.info("전원 완료: results={}", results);
        executor.shutdown();

        // check-then-act가 원자가 아니었다면 둘 다 START(게임 두 번 시작) 또는 둘 다 WAIT(영원히 대기)가 가능하다
        assertThat(results).hasSize(2);
        assertThat(results).filteredOn(r -> r.startsWith("START:")).hasSize(1);
        assertThat(results).filteredOn(r -> r.equals("WAIT")).hasSize(1);
        assertThat(results).filteredOn(r -> r.startsWith("START:")).containsExactly("START:1");

        // START와 함께 방 상태 전환도 한 몸으로 끝나 있어야 한다
        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        assertThat(room.get("status")).isEqualTo(RoomStatus.PLAYING.name());
        assertThat(room.get("round")).isEqualTo("1");
        // ready SET은 START 순간 삭제된다 — 남아 있으면 다음 판 판정이 오염된다
        assertThat(redisTemplate.hasKey(RoomRedisRepository.readyKey(roomCode))).isFalse();
    }

    @Test
    @DisplayName("게임 중(PLAYING)에 온 ready는 ALREADY_PLAYING으로 무시된다 — 재접속이 새 판을 시작하지 못한다")
    void readyDuringPlaying_ignored() {
        assertThat(roomRedisRepository.readyAtomic(roomCode, HOST_ID)).isEqualTo("WAIT");
        assertThat(roomRedisRepository.readyAtomic(roomCode, GUEST_ID)).isEqualTo("START:1");

        assertThat(roomRedisRepository.readyAtomic(roomCode, HOST_ID)).isEqualTo("ALREADY_PLAYING");
        assertThat(roomRedisRepository.findRoom(roomCode).get("round")).isEqualTo("1"); // round가 오르지 않았다
    }

    @Test
    @DisplayName("판 종료 후 다시 둘 다 ready하면 round가 증가한 START를 받는다 (연전)")
    void rematch_roundIncrements() {
        assertThat(roomRedisRepository.readyAtomic(roomCode, HOST_ID)).isEqualTo("WAIT");
        assertThat(roomRedisRepository.readyAtomic(roomCode, GUEST_ID)).isEqualTo("START:1");

        // 판 종료를 흉내 낸다 — Step 11이 구현되면 이 부분은 정산 로직 호출로 바뀐다
        redisTemplate.opsForHash().put(RoomRedisRepository.roomKey(roomCode), "status", RoomStatus.READY.name());

        assertThat(roomRedisRepository.readyAtomic(roomCode, GUEST_ID)).isEqualTo("WAIT");
        assertThat(roomRedisRepository.readyAtomic(roomCode, HOST_ID)).isEqualTo("START:2");
    }
}
