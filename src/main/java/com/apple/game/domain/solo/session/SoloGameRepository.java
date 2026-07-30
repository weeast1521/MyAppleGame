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

    /** SET solo:session:{id} {json} EX {ttl} */
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
     */
    public boolean delete(String gameSessionId) {
        return Boolean.TRUE.equals(redisTemplate.delete(key(gameSessionId)));
    }

    private String key(String gameSessionId) {
        return KEY_PREFIX + gameSessionId;
    }
}
