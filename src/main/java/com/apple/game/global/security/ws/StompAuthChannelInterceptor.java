package com.apple.game.global.security.ws;

import com.apple.game.global.metrics.ActiveUserTracker;
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

import java.security.Principal;
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
    private final ActiveUserTracker activeUserTracker;

    // channel로 보내기 직전(postSend(보낸 직후), afterSendCompletion(완료 후) 모두 default 구현이라 필요한 것만 구현)
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // StompHeaderAccessor: Message의 헤더를 STOMP 관점에서 읽기 좋게 감싸주는 헬퍼
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())){
            String token = resolveToken(accessor.getFirstNativeHeader("Authorization"));

            // access만 허용 — refresh도 같은 키로 서명돼 있어 validate()만 보면 CONNECT가 통과한다
            if (token == null || !jwtTokenProvider.validateAccessToken(token)) {
                throw new MessageDeliveryException("유효하지 않은 토큰입니다.");
            }

            Long userId = jwtTokenProvider.getUserId(token);

            accessor.setUser(new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, List.of()));
        }

        // 접속자 수 게이지(active_users)의 입력. 대전 중인 사람은 HTTP를 거의 안 보내고 STOMP 프레임만 보내므로
        // JWT 필터만으로는 5분 창에서 빠져나간다. CONNECT 때 심은 Principal이 이후 프레임마다 붙어 있으니 그 이름(userId)을 쓴다.
        recordActivity(accessor);
        return message;
    }

    private void recordActivity(StompHeaderAccessor accessor) {
        if (accessor == null) {
            return;
        }
        Principal user = accessor.getUser();
        if (user == null) {
            return;
        }
        try {
            activeUserTracker.touch(Long.valueOf(user.getName()));
        } catch (NumberFormatException ignored) {
            // 이 인터셉터가 심은 Principal은 항상 userId 문자열이다. 다른 형태면 세지 않고 넘어간다 — 지표 때문에 메시지를 막지 않는다.
        }
    }

    private String resolveToken(String header) {
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
