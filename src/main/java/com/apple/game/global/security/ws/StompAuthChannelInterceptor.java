package com.apple.game.global.security.ws;

import com.apple.game.global.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * STOMP CONNECT 프레임의 Authorization 헤더로 JWT를 검증한다.
 * REST와 달리 익명 통과가 없다 — 핸드셰이크 이후에는 헤더를 다시 받을 방법이 없어서
 * CONNECT 시점에 인증을 끝내고 Principal을 세션에 심어야 한다.
 * 여기서 setUser()로 심은 Principal은 이후 모든 프레임(SEND 등)에 자동으로 붙는다.
 */
@Component
@RequiredArgsConstructor
// Spring의 메시징 시스템에서 메시지가 채널을 통과하는 순간에 끼어들 수 있게 해주는 확장 지점(filter 역할)
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    // channel로 보내기 직전(postSend(보낸 직후), afterSendCompletion(완료 후) 모두 default 구현이라 필요한 것만 구현)
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // StompHeaderAccessor: Message의 헤더를 STOMP 관점에서 읽기 좋게 감싸주는 헬퍼
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())){
            String token = resolveToken(accessor.getFirstNativeHeader("Authorization"));

            if (token == null || !jwtTokenProvider.validate(token)) {
                throw new MessageDeliveryException("유효하지 않은 토큰입니다.");
            }

            Long userId = jwtTokenProvider.getUserId(token);

            accessor.setUser(new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, List.of()));
        }
        return message;
    }

    private String resolveToken(String header) {
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
