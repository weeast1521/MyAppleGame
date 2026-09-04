package com.apple.game.domain.ranking;

import com.apple.game.domain.ranking.dto.res.RankingResDTO;
import com.apple.game.domain.ranking.entity.RankingPeriod;
import com.apple.game.domain.ranking.repository.RankingRedisRepository;
import com.apple.game.domain.ranking.service.RankingService;
import com.apple.game.domain.solo.entity.SoloRecord;
import com.apple.game.domain.solo.repository.SoloRecordRepository;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Step 15 캐시 스탬피드 재현/방지 — 캐시가 빈 순간 N개 요청이 동시에 들어와도 DB 집계는 한 번만 실행되어야 한다.
 * 수정 전: 스레드마다 findAllTimeRanking()이 실행됐다(부하 테스트에서는 커넥션 풀 크기만큼 = 10~50번).
 * 수정 후: 락을 잡은 1개만 집계 + 적재, 나머지는 플래그를 기다렸다가 Redis에서 읽는다 — 결과는 전원 동일.
 *
 * 주의: 랭킹 키는 전역이라 로컬 DB의 다른 유저 기록도 함께 나온다. 검증은 '테스트 유저 3명이 모두 포함되는가'로 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class RankingWarmUpSingleFlightTest {

    private static final int THREADS = 20;

    @Autowired RankingService rankingService;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoSpyBean SoloRecordRepository soloRecordRepository;

    private final List<User> users = new ArrayList<>();
    private final List<SoloRecord> records = new ArrayList<>();
    private String key;

    @BeforeEach
    void setUp() {
        key = RankingPeriod.ALLTIME.redisKey(LocalDate.now(ZoneId.of("Asia/Seoul")));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        int[] scores = {900_000, 800_000, 700_000}; // 로컬 더미 데이터보다 확실히 높게 — 상위 100 안에 들도록
        for (int i = 0; i < 3; i++) {
            User u = userRepository.save(User.createLocalUser("sf-" + tag + "-" + i + "@test.com", "pw", "싱글" + tag.substring(0, 3) + i));
            users.add(u);
            records.add(soloRecordRepository.save(SoloRecord.create(u, scores[i], 10, 120, "seed")));
        }
        // 캐시 미스 상태에서 출발
        redisTemplate.delete(List.of(key, RankingRedisRepository.warmedKey(key), RankingRedisRepository.warmUpLockKey(key)));
    }

    @AfterEach
    void tearDown() {
        soloRecordRepository.deleteAll(records);
        userRepository.deleteAll(users);
        redisTemplate.delete(List.of(key, RankingRedisRepository.warmedKey(key), RankingRedisRepository.warmUpLockKey(key)));
    }

    @Test
    @DisplayName("캐시 미스에 20개 요청이 동시에 들어와도 DB 집계는 1번, 응답은 전원 동일")
    void warmUpRunsOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RankingResDTO.RankingPage>> futures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return rankingService.getRanking(null, "alltime", 0, 100);
            }));
        }
        ready.await();
        go.countDown(); // 전원 동시에 출발
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<String> expected = users.stream().map(User::getNickname).toList();
        int fromDb = 0, fromRedis = 0;
        for (Future<RankingResDTO.RankingPage> f : futures) {
            RankingResDTO.RankingPage page = f.get(); // 예외가 있었다면 여기서 터진다
            List<String> nicknames = page.rankings().stream().map(RankingResDTO.RankingItem::nickname).toList();
            assertThat(nicknames).containsAll(expected);
            if ("db".equals(page.source())) fromDb++; else fromRedis++;
        }

        // 핵심: 200만 건 집계 쿼리가 스레드 수만큼이 아니라 딱 한 번
        verify(soloRecordRepository, times(1)).findAllTimeRanking();
        assertThat(fromDb).isEqualTo(1);
        assertThat(fromRedis).isEqualTo(THREADS - 1);
        assertThat(redisTemplate.hasKey(RankingRedisRepository.warmUpLockKey(key))).isFalse(); // 락은 반납됐다
    }
}
