package com.apple.game.domain.solo;

import com.apple.game.domain.solo.dto.req.SoloReqDTO;
import com.apple.game.domain.solo.dto.res.SoloResDTO;
import com.apple.game.domain.solo.exception.SoloErrorCode;
import com.apple.game.domain.solo.repository.SoloRecordRepository;
import com.apple.game.domain.solo.service.SoloGameService;
import com.apple.game.domain.solo.session.SoloGameRepository;
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
 * #50 — "한 사람은 한 판만" 불변식을 서버가 지키는지 확인한다.
 *
 * 수정 전: start()가 클라이언트 변수만 덮어써서 서버에는 활성 세션이 여러 개 남았다.
 * 수정 후: solo:user:{userId} 포인터를 Lua로 원자 교체하며 직전 세션을 지운다.
 *          따라서 어떤 순서로 겹쳐도 (살아있는 세션 1개 + 포인터가 그것을 가리킴)이 된다.
 */
@SpringBootTest
@ActiveProfiles("local")
class SoloActiveSessionTest {

    @Autowired SoloGameService soloGameService;
    @Autowired SoloGameRepository soloGameRepository;
    @Autowired SoloRecordRepository soloRecordRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate stringRedisTemplate;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocalUser(
                "solo-active-" + UUID.randomUUID() + "@test.com", "pw", "활성세션"));
    }

    @AfterEach
    void tearDown() {
        stringRedisTemplate.delete("solo:user:" + user.getId());
        soloRecordRepository.deleteAll(soloRecordRepository.findAll().stream()
                .filter(r -> r.getUser().getId().equals(user.getId()))
                .toList());
        userRepository.delete(user);
    }

    @Test
    @DisplayName("새 판을 시작하면 이전 판은 즉시 무효 — 이전 세션 제출은 SOLO404")
    void startInvalidatesPreviousSession() {
        SoloResDTO.Start first = soloGameService.start(user.getId());
        SoloResDTO.Start second = soloGameService.start(user.getId());

        assertThat(soloGameRepository.find(first.gameSessionId())).isEmpty();
        assertThat(soloGameRepository.find(second.gameSessionId())).isPresent();

        // 버려진 판의 기록은 제출할 수 없다 = 저장되지 않는다
        assertThatThrownBy(() -> soloGameService.finish(
                user.getId(), first.gameSessionId(), new SoloReqDTO.Finish(List.of())))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("code", SoloErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("포인터는 항상 살아있는 그 세션을 가리킨다")
    void pointerTracksLiveSession() {
        SoloResDTO.Start start = soloGameService.start(user.getId());

        assertThat(stringRedisTemplate.opsForValue().get("solo:user:" + user.getId()))
                .isEqualTo(start.gameSessionId());
    }

    @Test
    @DisplayName("연타로 10개가 동시에 시작해도 살아남는 세션은 1개 — 포인터가 가리키는 것")
    void concurrentStartsLeaveExactlyOneSession() throws InterruptedException {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        var issued = new ConcurrentLinkedQueue<String>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.await();
                    issued.add(soloGameService.start(user.getId()).gameSessionId());
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        ready.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        List<String> alive = issued.stream()
                .filter(id -> soloGameRepository.find(id).isPresent())
                .toList();

        // 불변식: 활성 세션은 정확히 1개, 그리고 그것이 포인터가 가리키는 세션이다
        assertThat(alive).hasSize(1);
        assertThat(stringRedisTemplate.opsForValue().get("solo:user:" + user.getId()))
                .isEqualTo(alive.get(0));
    }

    @Test
    @DisplayName("판을 제출하면 포인터도 내려간다 — 진행 중인 판이 없는 상태")
    void finishClearsPointer() {
        SoloResDTO.Start start = soloGameService.start(user.getId());

        soloGameService.finish(user.getId(), start.gameSessionId(), new SoloReqDTO.Finish(List.of()));

        assertThat(stringRedisTemplate.opsForValue().get("solo:user:" + user.getId())).isNull();
    }

    @Test
    @DisplayName("제출 도중 새 판이 시작되면 옛 판 제출은 새 판의 포인터를 지우지 않는다")
    void finishDoesNotClearNewerPointer() {
        SoloResDTO.Start first = soloGameService.start(user.getId());
        SoloResDTO.Start second = soloGameService.start(user.getId());

        // 옛 판(first)은 이미 무효라 제출이 거부된다 — 이때 second의 포인터가 살아 있어야 한다
        assertThatThrownBy(() -> soloGameService.finish(
                user.getId(), first.gameSessionId(), new SoloReqDTO.Finish(List.of())))
                .isInstanceOf(CustomException.class);

        assertThat(stringRedisTemplate.opsForValue().get("solo:user:" + user.getId()))
                .isEqualTo(second.gameSessionId());
    }
}
