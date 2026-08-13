package com.apple.game.domain.ranking.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class RankingRedisRepository {

    private static final Duration WEEKLY_TTL = Duration.ofDays(14);

    private final StringRedisTemplate redisTemplate;

    // ZADD key GT score member — 기존 점수보다 클 때만 갱신(한 명씩, GT)
    public void updateScore(String key, Long userId, int score, boolean isWeekly) {
        byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
        byte[] rawMember = String.valueOf(userId).getBytes(StandardCharsets.UTF_8);

        redisTemplate.execute((RedisCallback<Void>) conn -> {
           conn.zSetCommands().zAdd(rawKey, score, rawMember, RedisZSetCommands.ZAddArgs.empty().gt());
           return null;
        });
        if (isWeekly) redisTemplate.expire(key, WEEKLY_TTL);
    }

    // ZREVRANGE key offset (offset+size-1) WITHSCORES — 반환 Set은 순서 보존(LinkedHashSet)
    public Set<ZSetOperations.TypedTuple<String>> topRange(String key, int offset, int size) {
        return redisTemplate.opsForZSet().reverseRangeWithScores(key, offset, offset + size - 1);
    }

    // ZREVRANK — 0-base, member 없으면 null
    public Long rankOf(String key, Long userId) {
        return redisTemplate.opsForZSet().reverseRank(key, String.valueOf(userId));
    }

    public Double scoreOf(String key, Long userId) {
        return redisTemplate.opsForZSet().score(key, String.valueOf(userId));
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /** warm-up: DB 집계 결과 전체를 ZADD 한 번으로 적재
     * Redis가 비어 있을 때 DB로부터 랭킹을 복구/구축하는 메서드
     * ex) 서버 재시작하는 경우 db에 조회할 사용자 값이 10000건인 경우 redis ZADD를 10000번 해야함.
     * 이걸 ZADD 명령 한 번으로 함(전원, 덮어쓰기)
     * ZADD key 950 "1" 800 "2" 720 "3" ...처럼 ZADD는 원래 여러 (score, member) 쌍을 한 명령에 받을 수 있음 */
    public void bulkLoad(String key, Map<Long, Integer> bestScoreByUserId, boolean isWeekly) {
        Set<ZSetOperations.TypedTuple<String>> tuples = bestScoreByUserId.entrySet().stream()
                .map(e -> ZSetOperations.TypedTuple.of(
                        String.valueOf(e.getKey()), (double) e.getValue()))
                .collect(Collectors.toSet());

        if (!tuples.isEmpty()) redisTemplate.opsForZSet().add(key, tuples);
        if (isWeekly) redisTemplate.expire(key, WEEKLY_TTL);
    }
}
