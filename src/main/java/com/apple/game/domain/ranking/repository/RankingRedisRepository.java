package com.apple.game.domain.ranking.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import org.springframework.data.redis.connection.zset.Tuple;

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

    // warm-up 완료 플래그. "ZSet 키가 존재한다"는 캐시가 완전하다는 뜻이 아니다 —
    // updateScore(단건 GT)도 키를 만들 수 있어, Redis 재시작 후 warm-up 전에 게임 하나가 끝나면
    // 멤버 1명짜리 부분 캐시가 생긴다. 완전 적재(bulkLoad)만 이 플래그를 세우고, 캐시 히트 판정은 이걸로 한다.
    public static String warmedKey(String key) { return key + ":warmed"; }

    // ZADD key GT score member — 기존 점수보다 클 때만 갱신(한 명씩, GT)
    public void updateScore(String key, Long userId, int score, boolean isWeekly) {
        byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
        byte[] rawMember = String.valueOf(userId).getBytes(StandardCharsets.UTF_8);

        redisTemplate.execute((RedisCallback<Void>) conn -> {
           conn.zSetCommands().zAdd(rawKey, score, rawMember, RedisZSetCommands.ZAddArgs.empty().gt());
           return null;
        });
        if (isWeekly) {
            redisTemplate.expire(key, WEEKLY_TTL);
            redisTemplate.expire(warmedKey(key), WEEKLY_TTL); // 플래그 수명을 데이터와 맞춘다 (없으면 no-op)
        }
    }

    // 캐시 히트 판정 — 키 존재가 아니라 warm-up 완료 여부
    public boolean isWarmed(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(warmedKey(key)));
    }

    // ---- warm-up 단일 실행(single-flight) 락 (Step 15) ----
    // 캐시가 빈 순간 들어온 N개 요청이 각자 200만 건 집계를 돌리는 캐시 스탬피드를 막는다.
    // 부하 테스트에서 실행 횟수가 정확히 HikariCP 풀 크기만큼(10, 50) 나왔다 — 풀이 우연히 상한 노릇을 했을 뿐
    // 풀을 키우면 오히려 집계가 그만큼 늘어 p95 2초 → 12초로 악화됐다(docs/load_test.md).
    // 락은 Redis에 둔다 — 로컬 락(synchronized)은 인스턴스가 늘면 인스턴스 수만큼 다시 스탬피드가 된다.
    // TTL은 락 보유자가 죽었을 때의 보험. 정상 경로에서는 finally에서 지운다.
    private static final Duration WARM_UP_LOCK_TTL = Duration.ofSeconds(30);

    public static String warmUpLockKey(String key) { return key + ":warmup-lock"; }

    // SET key 1 NX EX 30 — 획득 성공 시 true. 실패면 다른 요청이 이미 warm-up 중이다.
    public boolean tryLockWarmUp(String key) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(warmUpLockKey(key), "1", WARM_UP_LOCK_TTL));
    }

    public void unlockWarmUp(String key) {
        redisTemplate.delete(warmUpLockKey(key));
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
    // GT로 적재하는 이유: warm-up이 DB를 읽은 뒤 → ZADD 하기 전 사이에 누군가 게임을 끝내
    // updateScore(GT)로 더 높은 점수를 넣었을 수 있다. 덮어쓰기(기본 ZADD)면 그 최신 점수가 옛 DB 값으로
    // 되돌아간다(lost update). GT면 어느 쪽이 먼저든 높은 값이 남는다.
    // 플래그는 데이터 적재 '뒤'에 세운다 — 순서가 반대면 플래그는 있는데 ZSet이 빈 순간이 생긴다.
    public void bulkLoad(String key, Map<Long, Integer> bestScoreByUserId, boolean isWeekly) {
        Set<Tuple> tuples = bestScoreByUserId.entrySet().stream()
                .map(e -> Tuple.of(String.valueOf(e.getKey()).getBytes(StandardCharsets.UTF_8), (double) e.getValue()))
                .collect(Collectors.toSet());

        byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
        if (!tuples.isEmpty()) {
            redisTemplate.execute((RedisCallback<Void>) conn -> {
                conn.zSetCommands().zAdd(rawKey, tuples, RedisZSetCommands.ZAddArgs.empty().gt());
                return null;
            });
        }
        redisTemplate.opsForValue().set(warmedKey(key), "1"); // 기록이 0건이어도 'DB를 다 읽었다'는 사실은 참
        if (isWeekly) {
            redisTemplate.expire(key, WEEKLY_TTL);
            redisTemplate.expire(warmedKey(key), WEEKLY_TTL);
        }
    }
}
