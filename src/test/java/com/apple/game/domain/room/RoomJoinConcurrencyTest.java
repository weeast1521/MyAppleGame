package com.apple.game.domain.room;

import com.apple.game.domain.room.entity.RoomStatus;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.room.service.RoomService;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local") // 로컬 Redis(6379)·MySQL이 떠 있어야 한다
class RoomJoinConcurrencyTest {

    @Autowired RoomService roomService;
    @Autowired RoomRedisRepository roomRedisRepository;
    @Autowired UserRepository userRepository;

    private String roomCode;
    private List<User> users;

    @AfterEach
    void tearDown() {
        if (roomCode != null) roomRedisRepository.deleteRoom(roomCode);
        if (users != null) userRepository.deleteAll(users);
    }

    @Test
    @DisplayName("두 유저가 동시에 join하면 정확히 한 명만 성공한다")
    void concurrentJoin_onlyOneSucceeds() throws Exception {
        User host = saveUser("host");
        User guestA = saveUser("guestA");
        User guestB = saveUser("guestB");
        users = List.of(host, guestA, guestB);

        roomCode = roomService.create(host.getId()).roomCode();

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);   // 두 스레드를 같은 순간에 출발시킨다
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        for (Long userId : List.of(guestA.getId(), guestB.getId())) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();                       // 출발선에서 대기
                    roomService.join(userId, roomCode);
                    success.incrementAndGet();
                } catch (CustomException e) {
                    conflict.incrementAndGet();          // ROOM409 기대
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();                               // 동시 출발!
        done.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 1차(락 없음) 구현에선 success == 2 로 여기서 실패한다 → 레이스 재현
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(roomRedisRepository.findRoom(roomCode).get("status"))
                .isEqualTo(RoomStatus.READY.name());
    }

    private User saveUser(String name) {
        return userRepository.save(
                User.createLocalUser(name + "@test.com", "encoded-password", name));
    }
}
