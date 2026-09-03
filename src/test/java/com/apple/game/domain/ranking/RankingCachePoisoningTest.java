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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #12 재현: Redis 재시작(키 삭제) → warm-up 전에 게임 한 판 종료(updateScore) → 랭킹 조회.
 * 수정 전: 멤버 1명짜리 ZSet을 완전한 캐시로 믿어 1명만 나온다.
 * 수정 후: warm-up 플래그가 없으므로 DB 집계 + 전체 적재 → 전원이 나온다.
 *
 * 주의: 랭킹 키는 전역(ranking:solo:alltime)이라 로컬 DB의 다른 유저 기록도 함께 나온다.
 * 검증은 '테스트 유저 3명이 모두 포함되는가'로 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class RankingCachePoisoningTest {

    @Autowired RankingService rankingService;
    @Autowired RankingRedisRepository rankingRedisRepository;
    @Autowired SoloRecordRepository soloRecordRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redisTemplate;

    private final List<User> users = new ArrayList<>();
    private final List<SoloRecord> records = new ArrayList<>();
    private String key;

    @BeforeEach
    void setUp() {
        key = RankingPeriod.ALLTIME.redisKey(LocalDate.now(ZoneId.of("Asia/Seoul")));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        int[] scores = {900_000, 800_000, 700_000}; // 로컬 더미 데이터보다 확실히 높게 — 상위 100 안에 들도록
        for (int i = 0; i < 3; i++) {
            User u = userRepository.save(User.createLocalUser("rk-" + tag + "-" + i + "@test.com", "pw", "랭크" + tag.substring(0, 3) + i));
            users.add(u);
            records.add(soloRecordRepository.save(SoloRecord.create(u, scores[i], 10, 120, "seed")));
        }
    }

    @AfterEach
    void tearDown() {
        soloRecordRepository.deleteAll(records);
        userRepository.deleteAll(users);
        // 테스트가 만든 캐시 상태를 지워 다음 조회가 정상 warm-up 하게
        redisTemplate.delete(List.of(key, RankingRedisRepository.warmedKey(key)));
    }

    private List<String> topNicknames() {
        RankingResDTO.RankingPage page = rankingService.getRanking(null, "alltime", 0, 100);
        return page.rankings().stream().map(RankingResDTO.RankingItem::nickname).toList();
    }

    @Test
    @DisplayName("#12 재현 — 캐시 소멸 후 warm-up 전에 단건 갱신이 먼저 와도, 조회는 전원을 돌려준다")
    void partialCacheIsNotTreatedAsWarm() {
        // 1) Redis 재시작 상황: 랭킹 키·플래그 소멸
        redisTemplate.delete(List.of(key, RankingRedisRepository.warmedKey(key)));

        // 2) warm-up 전에 유저 0이 게임을 끝냄 → updateScore(GT)가 멤버 1명짜리 키를 만든다
        rankingRedisRepository.updateScore(key, users.get(0).getId(), 900_000, false);
        assertThat(rankingRedisRepository.exists(key)).as("키는 존재한다 — 예전 판정이면 여기서 캐시 히트").isTrue();
        assertThat(rankingRedisRepository.isWarmed(key)).as("하지만 warm-up은 안 됐다").isFalse();

        // 3) 조회 — 플래그가 없으므로 DB 집계 + 전체 적재
        RankingResDTO.RankingPage first = rankingService.getRanking(null, "alltime", 0, 100);
        assertThat(first.source()).isEqualTo("db");
        assertThat(topNicknames()).containsAll(users.stream().map(User::getNickname).toList());

        // 4) 이후 조회는 Redis에서, 여전히 전원
        RankingResDTO.RankingPage second = rankingService.getRanking(null, "alltime", 0, 100);
        assertThat(second.source()).isEqualTo("redis");
        assertThat(second.rankings().stream().map(RankingResDTO.RankingItem::nickname).toList())
                .containsAll(users.stream().map(User::getNickname).toList());
    }

    @Test
    @DisplayName("warm-up(bulkLoad)은 GT — 그 사이 들어온 더 높은 단건 점수를 옛 DB 값으로 되돌리지 않는다")
    void bulkLoadDoesNotLowerFresherScore() {
        redisTemplate.delete(List.of(key, RankingRedisRepository.warmedKey(key)));
        Long uid = users.get(0).getId();

        rankingRedisRepository.updateScore(key, uid, 950_000, false);          // warm-up이 DB를 읽은 '뒤' 끝난 게임
        rankingRedisRepository.bulkLoad(key, Map.of(uid, 900_000), false);    // DB 스냅샷(옛 값)으로 적재

        assertThat(rankingRedisRepository.scoreOf(key, uid)).as("높은 최신 점수가 남아야 한다").isEqualTo(950_000.0);
        assertThat(rankingRedisRepository.isWarmed(key)).isTrue();
    }

    @Test
    @DisplayName("플래그는 데이터 적재 뒤에 세워진다 — 기록 0건이어도 warm-up 완료로 본다")
    void emptyWarmupStillMarksWarmed() {
        String tmp = key + ":test:" + UUID.randomUUID();
        try {
            rankingRedisRepository.bulkLoad(tmp, Map.of(), false);
            assertThat(rankingRedisRepository.exists(tmp)).isFalse();
            assertThat(rankingRedisRepository.isWarmed(tmp)).isTrue();
        } finally {
            redisTemplate.delete(List.of(tmp, RankingRedisRepository.warmedKey(tmp)));
        }
    }
}
