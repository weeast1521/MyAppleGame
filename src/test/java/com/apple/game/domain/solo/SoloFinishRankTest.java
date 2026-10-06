package com.apple.game.domain.solo;

import com.apple.game.domain.ranking.entity.RankingPeriod;
import com.apple.game.domain.solo.dto.req.SoloReqDTO;
import com.apple.game.domain.solo.dto.res.SoloResDTO;
import com.apple.game.domain.solo.entity.SoloRecord;
import com.apple.game.domain.solo.repository.SoloRecordRepository;
import com.apple.game.domain.solo.service.SoloGameService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * finish 응답의 allTimeRank는 '내 최고점' 기준이어야 한다 — 랭킹은 유저당 최고점 한 줄이다.
 *
 * 수정 전: 이번 판 점수로 countUsersWithScoreAbove를 불러서, 최고점을 못 넘긴 판에서는
 *          내 예전 최고 기록이 '나보다 위'로 세어졌다. 결과 화면은 3위인데 랭킹 탭은 2위인 식으로 어긋났다.
 * 수정 후: max(이번 점수, 직전 최고점)으로 세므로 결과 화면·summary·랭킹 탭의 순위가 일치한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class SoloFinishRankTest {

    // 보드 17x10의 만점 — 누구도 이 점수를 넘을 수 없으므로 이 유저의 순위는 항상 1위다
    private static final int MAX_SCORE = 170;

    @Autowired SoloGameService soloGameService;
    @Autowired SoloRecordRepository soloRecordRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate stringRedisTemplate;

    private User user;
    private final List<Long> recordIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocalUser(
                "solo-rank-" + UUID.randomUUID() + "@test.com", "pw", "순위검증"));
    }

    @AfterEach
    void tearDown() {
        // 만든 기록만 id로 지운다 — findAll()로 걸러내면 더미 200만 건을 전부 읽어온다
        soloRecordRepository.deleteAllByIdInBatch(recordIds);
        // finish가 랭킹 ZSet에 넣은 멤버도 지운다 — 남겨두면 삭제된 유저가 랭킹 캐시에 떠돈다
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (RankingPeriod period : RankingPeriod.values()) {
            stringRedisTemplate.opsForZSet().remove(period.redisKey(today), String.valueOf(user.getId()));
        }
        userRepository.delete(user);
    }

    @Test
    @DisplayName("최고점을 못 넘긴 판의 순위는 이번 점수가 아니라 내 최고점 기준 — summary 순위와 일치한다")
    void rankIsBasedOnBestScoreWhenNotPersonalBest() {
        recordIds.add(soloRecordRepository.save(SoloRecord.create(user, MAX_SCORE, 85, 120, "0")).getId());

        // moves가 비어 있으면 점수 0 — 최고점(170)에 한참 못 미치는 판
        SoloResDTO.Start start = soloGameService.start(user.getId());
        SoloResDTO.Finish finish = soloGameService.finish(
                user.getId(), start.gameSessionId(), new SoloReqDTO.Finish(List.of()));
        recordIds.add(finish.recordId());

        assertThat(finish.isPersonalBest()).isFalse();
        // 개인 최고는 이번 판 점수(0)가 아니라 그동안의 최고점
        assertThat(finish.bestScore()).isEqualTo(MAX_SCORE);
        // 수정 전에는 0점 기준으로 세어 '0점보다 높은 유저 수(나 포함) + 1'이 나왔다
        assertThat(finish.allTimeRank()).isEqualTo(1);
        assertThat(finish.allTimeRank()).isEqualTo(soloGameService.getSummary(user.getId()).allTimeRank());
    }
}
