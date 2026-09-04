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
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

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

    // 대기자가 warm-up 플래그를 기다리는 상한과 폴링 간격. 단독 warm-up은 수십 ms~2초 안에 끝난다(Step 14 E2).
    private static final long WARM_UP_WAIT_MS = 5_000;
    private static final long WARM_UP_POLL_MS = 50;

    // Step 15: 서비스 레벨 @Transactional을 뗐다. 이 메서드는 대부분 Redis에서 끝나는데, 트랜잭션이 걸려 있으면
    // 진입 즉시 DB 커넥션을 잡고(Hibernate가 begin 시점에 autocommit을 끄려고 커넥션을 얻는다) Redis 조회·락 대기
    // 내내 붙들고 있다 — 200 VU 동시 진입에서 hikari pending 149가 그 결과였다. 필요한 DB 조회는
    // 각 repository 메서드가 자기 읽기 트랜잭션 안에서 짧게 한다.
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
                : loadWithSingleFlight(key, period, today, userId, offset, size);

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

    // 캐시 미스 — 캐시 스탬피드 방지(Step 15).
    // 락을 잡은 한 요청만 DB 집계 + 적재를 하고, 나머지는 플래그가 설 때까지 짧게 폴링한 뒤 Redis에서 읽는다.
    // 수정 전: 200 VU 동시 진입 시 집계가 풀 크기만큼(10~50번) 중복 실행되어 각 1.7~8초, p95 2~12초.
    // 수정 후: 집계 1번, 나머지는 그 완료를 기다렸다가 캐시 히트 경로로 빠진다.
    private RankingResDTO.RankingPage loadWithSingleFlight(
            String key, RankingPeriod period, LocalDate today, Long userId, int offset, int size) {
        if (rankingRedisRepository.tryLockWarmUp(key)) {
            try {
                return loadFromDb(key, period, today, userId, offset, size, true);
            } finally {
                rankingRedisRepository.unlockWarmUp(key);
            }
        }

        long deadline = System.currentTimeMillis() + WARM_UP_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (rankingRedisRepository.isWarmed(key)) {
                return loadFromRedis(key, userId, offset, size);
            }
            try {
                Thread.sleep(WARM_UP_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 보유자가 죽었거나(락은 TTL로 풀린다) 집계가 비정상적으로 느리다 —
        // 이 요청은 적재 없이 DB에서 직접 답하고, 다음 요청이 락을 다시 시도한다.
        log.warn("랭킹 warm-up 대기 초과 key={} — 적재 없이 DB 직접 응답", key);
        return loadFromDb(key, period, today, userId, offset, size, false);
    }

    private RankingResDTO.RankingPage loadFromDb(
            String key, RankingPeriod period, LocalDate today, Long userId, int offset, int size, boolean warmUp) {
        LocalDateTime from = period.aggregateFrom(today);

        // 전체를 조회하는 이유: 결과 전원을 ZSet에 적재(warm-up)해야 이후 조회가 Redis에서 끝난다
        List<SoloRecordRepository.RankingRow> rows = (from == null)
                ? soloRecordRepository.findAllTimeRanking()
                : soloRecordRepository.findWeeklyRanking(from);

        if (warmUp) {
            Map<Long, Integer> scores = rows.stream().collect(Collectors.toMap(
                    SoloRecordRepository.RankingRow::getUserId,
                    SoloRecordRepository.RankingRow::getBestScore));

            rankingRedisRepository.bulkLoad(key, scores, period == RankingPeriod.WEEKLY);
        }

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
