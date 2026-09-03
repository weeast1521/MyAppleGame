package com.apple.game.domain.ranking.service;

import com.apple.game.domain.ranking.dto.res.RankingResDTO;
import com.apple.game.domain.ranking.entity.RankingPeriod;
import com.apple.game.domain.ranking.repository.RankingRedisRepository;
import com.apple.game.domain.solo.entity.SoloRecord;
import com.apple.game.domain.solo.repository.SoloRecordRepository;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    private static final int MAX_RANGE = 100;

    private final SoloRecordRepository soloRecordRepository;
    private final RankingRedisRepository rankingRedisRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public RankingResDTO.RankingPage getRanking(Long userId, String periodParam, int offset, int size) {
        long startedAt = System.currentTimeMillis();

        RankingPeriod period = RankingPeriod.from(periodParam);
        // 현재 로컬에서는 괜찮지만 EC2의 경우 UTC가 기본이기에 따로 설정이 필요
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        String key = period.redisKey(today);
        offset = Math.max(0, Math.min(offset, MAX_RANGE));
        size = Math.max(1, Math.min(size, MAX_RANGE - offset));

        // 캐시 히트 -> redis 응답 / 미스 -> db 집계로 응답 + redis 재적재.
        // 판정은 '키 존재'가 아니라 'warm-up 완료 플래그' — 키 존재로 판정하면 warm-up 전에 끝난 게임 한 판이
        // 만든 멤버 1명짜리 ZSet을 완전한 캐시로 믿어 영원히 DB로 돌아가지 않는다 (#12).
        RankingResDTO.RankingPage result = rankingRedisRepository.isWarmed(key)
                ? loadFromRedis(key, userId, offset, size)
                : loadFromDbAndWarmUp(key, period, today, userId, offset, size);

        log.info("랭킹 조회 period={} source={} elapsed={}ms",
                period, result.source(), System.currentTimeMillis() - startedAt);

        return result;
    }

    private RankingResDTO.RankingPage loadFromRedis(String key, Long userId, int offset, int size) {
        Set<ZSetOperations.TypedTuple<String>> tuples = rankingRedisRepository.topRange(key, offset, size);

        // ZSet member는 userId 문자열뿐 → 닉네임을 IN 쿼리 한 번으로 채운다
        List<Long> ids = tuples.stream().map(t -> Long.valueOf(t.getValue())).toList();
        Map<Long, String> nicknames = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));

        List<RankingResDTO.RankingItem> rankings = new ArrayList<>();
        int rank = offset + 1;
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            rankings.add(new RankingResDTO.RankingItem(
                    rank++, nicknames.get(Long.valueOf(t.getValue())), t.getScore().intValue()));
        }

        RankingResDTO.MyRank myRank = null;
        if (userId != null) {
            Long zeroBased = rankingRedisRepository.rankOf(key, userId);  // 0-base, 없으면 null
            if (zeroBased != null) {
                Double score = rankingRedisRepository.scoreOf(key, userId);
                myRank = new RankingResDTO.MyRank(zeroBased.intValue() + 1, score.intValue());
            }
        }
        return new RankingResDTO.RankingPage("redis", rankings, myRank);
    }

    private RankingResDTO.RankingPage loadFromDbAndWarmUp(
            String key, RankingPeriod period, LocalDate today, Long userId, int offset, int size) {
        LocalDateTime from = period.aggregateFrom(today);

        // PageRequest.of(0, offset + size)에서 unpaged()로 전체 조희를 함 -> 이전에는 db에서 count를 했지만 이제는 ZSet으로 전체를 조회하기 때문
        List<SoloRecordRepository.RankingRow> rows = (from == null)
                ? soloRecordRepository.findAllTimeRanking(Pageable.unpaged())
                : soloRecordRepository.findWeeklyRanking(from, Pageable.unpaged());

        Map<Long, Integer> scores = rows.stream().collect(Collectors.toMap(
                SoloRecordRepository.RankingRow::getUserId,
                SoloRecordRepository.RankingRow::getBestScore));

        rankingRedisRepository.bulkLoad(key, scores, period == RankingPeriod.WEEKLY);

        List<RankingResDTO.RankingItem> rankings = new ArrayList<>();
        for (int i = offset; i < Math.min(offset + size, rows.size()); i++) {
            var row = rows.get(i);
            rankings.add(new RankingResDTO.RankingItem(i + 1, row.getNickname(), row.getBestScore()));
        }

        RankingResDTO.MyRank myRank = null;
        if (userId != null) {
            Integer myBest = (from == null)
                    ? soloRecordRepository.findTopByUserIdOrderByScoreDesc(userId)
                    .map(SoloRecord::getScore).orElse(null)
                    : soloRecordRepository.findMyWeeklyBestScore(userId, from);
            if (myBest != null) {
                long above = (from == null)
                        ? soloRecordRepository.countUsersWithScoreAbove(myBest)
                        : soloRecordRepository.findMyWeeklyRanking(from, myBest);
                myRank = new RankingResDTO.MyRank((int) above + 1, myBest);
            }
        }
        return new RankingResDTO.RankingPage("db", rankings, myRank);
    }
}
