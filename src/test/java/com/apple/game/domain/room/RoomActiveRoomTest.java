package com.apple.game.domain.room;

import com.apple.game.domain.room.exception.RoomErrorCode;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.room.service.RoomService;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #58 — "한 사람은 방 하나" 불변식을 서버가 지키는지 확인한다. #50(솔로 활성 세션)과 같은 구조.
 *
 * 수정 전: create()가 유저당 방 개수를 보지 않아 POST /api/rooms 를 반복하면 방이 무한히 쌓였다
 *          (TTL 6시간, noeviction Redis → 가득 차면 전 사용자의 쓰기 실패).
 * 수정 후: user:{id}:room 포인터를 Lua 로 원자 교체하고, 직전 방이 '나 혼자 WAITING' 이면 지운다.
 *          상대가 있는 방이면 새 방을 거두고 ROOM409_2 로 거절한다.
 */
@SpringBootTest
@ActiveProfiles("local") // 로컬 Redis(6379)·MySQL이 떠 있어야 한다
class RoomActiveRoomTest {

    @Autowired RoomService roomService;
    @Autowired RoomRedisRepository roomRedisRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redis;

    private User host;
    private User guest;
    private final ConcurrentLinkedQueue<String> createdRooms = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        host = saveUser("host");
        guest = saveUser("guest");
    }

    @AfterEach
    void tearDown() {
        createdRooms.forEach(roomRedisRepository::deleteRoom);
        redis.delete(List.of(RoomRedisRepository.userRoomKey(host.getId()), RoomRedisRepository.userRoomKey(guest.getId())));
        userRepository.deleteAll(List.of(host, guest));
    }

    @Test
    @DisplayName("혼자 기다리던 방이 있는데 새 방을 만들면 이전 방은 지워진다 — 포인터는 새 방")
    void createReplacesAbandonedRoom() {
        String first = create(host);
        String second = create(host);

        assertThat(roomRedisRepository.findRoom(first)).isEmpty();
        assertThat(roomRedisRepository.findRoom(second)).isNotEmpty();
        assertThat(roomRedisRepository.findActiveRoom(host.getId())).contains(second);
    }

    @Test
    @DisplayName("연타로 10개가 동시에 생성돼도 살아남는 방은 1개 — 포인터가 가리키는 것")
    void concurrentCreatesLeaveExactlyOneRoom() throws InterruptedException {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.await();
                    create(host);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        ready.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        List<String> alive = createdRooms.stream()
                .filter(code -> !roomRedisRepository.findRoom(code).isEmpty())
                .toList();

        // 불변식: 살아있는 방은 정확히 1개, 그리고 그것이 포인터가 가리키는 방이다
        assertThat(alive).hasSize(1);
        assertThat(roomRedisRepository.findActiveRoom(host.getId())).contains(alive.get(0));
    }

    @Test
    @DisplayName("상대가 들어온 방(READY)이 있으면 새 방은 거절 — 기존 방 그대로, 새 방 없음, 포인터 유지")
    void createRejectedWhileInRoomWithOpponent() {
        String room = create(host);
        roomService.join(guest.getId(), room);

        assertThatThrownBy(() -> create(host))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("code", RoomErrorCode.ROOM_ALREADY_IN);

        assertThat(roomRedisRepository.findRoom(room)).containsEntry("guestId", String.valueOf(guest.getId()));
        assertThat(roomRedisRepository.findActiveRoom(host.getId())).contains(room);
        // 거절되며 거둔 새 방은 남지 않는다
        assertThat(redis.keys("room:*")).noneMatch(k -> !k.startsWith("room:" + room) && createdRooms.contains(k.substring(5)));
    }

    @Test
    @DisplayName("방을 나가면 포인터가 내려가고 다시 만들 수 있다")
    void leaveClearsPointer() {
        String room = create(host);
        roomService.join(guest.getId(), room);

        roomService.leave(host.getId(), room);

        assertThat(roomRedisRepository.findActiveRoom(host.getId())).isEmpty();
        assertThat(roomRedisRepository.findRoom(create(host))).isNotEmpty();
    }

    @Test
    @DisplayName("게스트로 입장하면 혼자 기다리던 내 방은 지워진다")
    void joinReleasesMyAbandonedRoom() {
        String mine = create(host);
        String theirs = create(guest);

        roomService.join(host.getId(), theirs);

        assertThat(roomRedisRepository.findRoom(mine)).isEmpty();
        assertThat(roomRedisRepository.findActiveRoom(host.getId())).contains(theirs);
    }

    @Test
    @DisplayName("포인터만 남고 방이 사라진 경우(TTL 소멸)에는 그냥 새 방을 만든다")
    void stalePointerIsIgnored() {
        String first = create(host);
        roomRedisRepository.deleteRoom(first); // TTL 소멸을 흉내 — 포인터는 아직 first 를 가리킨다
        assertThat(roomRedisRepository.findActiveRoom(host.getId())).contains(first);

        String second = create(host);

        assertThat(roomRedisRepository.findRoom(second)).isNotEmpty();
        assertThat(roomRedisRepository.findActiveRoom(host.getId())).contains(second);
    }

    private String create(User user) {
        String code = roomService.create(user.getId()).roomCode();
        createdRooms.add(code);
        return code;
    }

    private User saveUser(String tag) {
        return userRepository.save(User.createLocalUser(
                "room-active-" + tag + "-" + UUID.randomUUID() + "@test.com", "pw", tag + UUID.randomUUID().toString().substring(0, 6)));
    }
}
