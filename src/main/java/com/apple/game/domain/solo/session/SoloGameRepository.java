package com.apple.game.domain.solo.session;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * solo:session:{gameSessionId} 키로 세션을 저장/조회/삭제한다.
 * 도메인 코드는 이 클래스만 알고, Redis 명령·키 구조는 여기에만 존재한다.
 */
@Repository
@RequiredArgsConstructor
public class SoloGameRepository {

    private static final String KEY_PREFIX = "solo:session:";

    private final RedisTemplate<String, Object> redisTemplate;

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

    private String key(String gameSessionId) {
        return KEY_PREFIX + gameSessionId;
    }
}
