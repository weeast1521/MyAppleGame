package com.apple.game.global.security.ws;

import com.apple.game.domain.user.entity.Role;
import com.apple.game.global.security.jwt.JwtProperties;
import com.apple.game.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StompAuthChannelInterceptor 계약:
 *  - CONNECT 프레임의 Authorization이 access 토큰이면 userId Principal을 세션에 심는다
 *  - refresh·토큰 없음이면 MessageDeliveryException으로 연결을 거부한다
 */
class StompAuthChannelInterceptorTest {

    final JwtTokenProvider provider = new JwtTokenProvider(new JwtProperties(
            Base64.getEncoder().encodeToString("test-secret-key-must-be-at-least-32-bytes!".getBytes(StandardCharsets.UTF_8)),
            Duration.ofMinutes(30), Duration.ofDays(14)));
    final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(provider);

    /** Authorization 헤더를 단 CONNECT 프레임. accessor를 mutable로 둬야 인터셉터가 같은 accessor를 꺼내 setUser한다 */
    static StompHeaderAccessor connectAccessor(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (token != null) accessor.setNativeHeader("Authorization", "Bearer " + token);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    static Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("access 토큰이면 userId를 Principal로 심는다")
    void accessTokenConnects() {
        StompHeaderAccessor accessor = connectAccessor(provider.createAccessToken(7L, Role.USER));

        interceptor.preSend(message(accessor), null);

        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("7");
    }

    @Test
    @DisplayName("refresh 토큰으로는 CONNECT할 수 없다")
    void refreshTokenRejected() {
        StompHeaderAccessor accessor = connectAccessor(provider.createRefreshToken(7L));

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("토큰이 없으면 CONNECT를 거부한다")
    void missingTokenRejected() {
        StompHeaderAccessor accessor = connectAccessor(null);

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class);
    }
}
