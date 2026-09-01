package com.apple.game.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 클라이언트 — Step 10 2차(분산 락) 비교 구현에서 RLock을 얻기 위한 용도.
 *
 * Spring Data Redis(Lettuce)가 이미 StringRedisTemplate로 모든 명령을 처리하고 있으므로
 * Redisson은 "락 전용" 두 번째 커넥션으로만 붙인다. 접속 정보는 spring.data.redis.*를 그대로 읽어
 * 로컬(localhost:6379)·prod(REDIS_HOST 환경변수) 어디서든 설정을 두 벌 관리하지 않는다.
 *
 * destroyMethod = "shutdown": 컨텍스트 종료 시 Redisson의 Netty 스레드·커넥션을 정리한다.
 * (없으면 테스트 JVM이 바로 안 죽거나 커넥션 누수 경고가 뜬다)
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        SingleServerConfig single = config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setConnectTimeout(3_000)
                .setTimeout(3_000);

        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            single.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }
}
