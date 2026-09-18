package com.apple.game.domain.solo.session;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * solo:session:{gameSessionId} 로 세션을, solo:user:{userId} 로 그 유저의 활성 세션 포인터를 다룬다.
 * 도메인 코드는 이 클래스만 알고, Redis 명령·키 구조는 여기에만 존재한다.
 *
 * 키가 두 개인 이유: "유저당 활성 세션은 1개"라는 불변식을 서버가 지키려면
 * 세션 id만으로는 부족하다 — 어느 세션이 그 유저의 현재 판인지 가리키는 값이 필요하다(#50).
 */
@Repository
@RequiredArgsConstructor
public class SoloGameRepository {

    private static final String KEY_PREFIX = "solo:session:";
    private static final String USER_KEY_PREFIX = "solo:user:";

    private final RedisTemplate<String, Object> redisTemplate;
    // 포인터는 세션 id 문자열 하나뿐이라 JSON 직렬화가 필요 없다 — Lua에서 값을 그대로 비교하기도 쉽다
    private final StringRedisTemplate stringRedisTemplate;

    // 활성 세션 포인터를 새 세션으로 교체하고 직전 값을 돌려준다.
    // GET과 SET을 Lua로 묶는 이유: 두 요청이 동시에 '새 게임'을 눌러도 각자 서로 다른 직전 값을
    // 받으므로 정확히 하나씩만 정리한다. 마지막에 SET한 요청의 세션이 살아남고, 그 세션이
    // 곧 포인터가 가리키는 세션이다 — 불변식(활성 세션 ≤ 1)이 경합에서도 깨지지 않는다.
    // (GETSET/SET..GET 단일 명령으로도 되지만, 만료 시간을 함께 주려면 Lua가 명확하다)
    private static final DefaultRedisScript<String> SWAP_ACTIVE_SCRIPT = new DefaultRedisScript<>("""
            local previous = redis.call('GET', KEYS[1])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            if previous == false then
                return ''
            end
            return previous
            """, String.class);

    // 포인터가 '이 세션'을 가리킬 때만 지운다(compare-and-delete).
    // 무조건 DEL하면, 제출하는 사이에 사용자가 새 판을 시작한 경우 새 판의 포인터를 지워버린다.
    private static final DefaultRedisScript<Long> CLEAR_ACTIVE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /** SET solo:session:{id} {json} EX {ttl}
     * {
     *   "@class": "com.apple.game.domain.solo.session.SoloGameSession",
     *   "gameSessionId": "abc123",
     *   "userId": 1,
     *   "boardSeed": 987654321,
     *   "startedAtMills": 1753924800000
     * }
     *
     * "@class" => GenericJackson2JsonRedisSerializer가 역직렬화할 때 어떤 클래스로 되돌릴지 알기 위해 자동으로 넣는 타입 정보
     * ttl은 ex 옵션으로 지정되는 키의 만료 시간
     */
    public void save(SoloGameSession session, Duration ttl) {
        redisTemplate.opsForValue().set(key(session.getGameSessionId()), session, ttl);
    }

    /** GET solo:session:{id} — 없거나 TTL 만료면 empty */
    public Optional<SoloGameSession> find(String gameSessionId) {
        Object value = redisTemplate.opsForValue().get(key(gameSessionId));

        return Optional.ofNullable((SoloGameSession) value);
    }

    /**
     * DEL solo:session:{id}
     * @return 실제로 키가 지워졌으면 true — "이미 없었음"과 구분된다
     * redisTemplate.delete는 Boolean을 반환하므로 true, false, null 이 가능
     * Boolean.TRUE.equals(null) -> "true가 null이랑 같아?" → false (터지지 않음)
     */
    public boolean delete(String gameSessionId) {
        return Boolean.TRUE.equals(redisTemplate.delete(key(gameSessionId)));
    }

    /**
     * 활성 세션 포인터를 gameSessionId로 교체하고, 교체 전에 가리키던 세션 id를 돌려준다.
     * @return 직전 활성 세션 id. 없었으면 empty
     */
    public Optional<String> swapActiveSession(Long userId, String gameSessionId, Duration ttl) {
        String previous = stringRedisTemplate.execute(
                SWAP_ACTIVE_SCRIPT,
                List.of(userKey(userId)),
                gameSessionId, String.valueOf(ttl.toSeconds()));

        return (previous == null || previous.isEmpty()) ? Optional.empty() : Optional.of(previous);
    }

    /** 포인터가 이 세션을 가리키고 있을 때만 지운다 — 판이 끝났음을 표시한다 */
    public void clearActiveSession(Long userId, String gameSessionId) {
        stringRedisTemplate.execute(CLEAR_ACTIVE_SCRIPT, List.of(userKey(userId)), gameSessionId);
    }

    private String key(String gameSessionId) {
        return KEY_PREFIX + gameSessionId;
    }

    private String userKey(Long userId) {
        return USER_KEY_PREFIX + userId;
    }
}
