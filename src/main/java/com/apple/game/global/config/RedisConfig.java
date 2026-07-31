package com.apple.game.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
    // Java 객체를 Redis에 넣고 빼는 작업을 담당(redis 커넥션 관리, 명령, 실행, 직렬화를 한 곳에 묶어서 편이하게 해줌)
    // 1. 커넥션을 알아서 얻고 반납
    // 2. 직렬화(redis는 byte만 저장 가능하기에 Map, Hash 같은 객체들을 넣을 수 없음) -> 객체를 byte로(직렬화)
    //    => 변환기를 무엇으로 쓸지 정하는 게 이 RedisConfig
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        return template;
    }
}
