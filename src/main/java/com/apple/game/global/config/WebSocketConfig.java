package com.apple.game.global.config;

import com.apple.game.global.security.ws.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@RequiredArgsConstructor
@EnableWebSocketMessageBroker // STOMP 메시징 인프라 전체를 켬
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    // 입구 정의 - 프론트의 new SockJS('/ws')가 이 주소로 업그레이드 요청을 보내고, 여기서 101 응답을 받아 WebSocket 연결이 열림
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry
                .addEndpoint("/ws") // /ws가 핸드셰이크가 일어나는 HTTP 엔드포인트
                .withSockJS(); // fallback 장치: websocket을 못쓰는 환경인 경우 자동으로 http 롱 폴링 같은 대체 전송 방식 사용
    }

    // destination prefix에 따라 메시지가 어디로 갈지 정해짐(STOMP 프레임의 destination 헤더가 어떻게 해석되는지 여기서 결정)
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        /*
            /topic = 방 전체 브로드캐스트, /queue = 개인 메시지(에러)
            SimpleBroker: 구독 목록을 기억해뒀다가, 해당 destination으로 메시지가 오면 구독자 전원에게 뿌려줌
         */
        registry.enableSimpleBroker("/topic", "/queue"); // 나가는 문

        /*
            클라이언트 발행(@MessageMapping 라우팅) prefix
            ex)
            1. 클라이언트가 destination:/app/game/123/move로 SEND
            2. 이 prefix 때문에 브로커가 아니라 애플리케이션 코드, 즉 @MessageMapping 컨트롤러로 라우팅됨
            3. Spring은 prefix를 뗀 나머지(/game/123/move)로 매핑
            "서버 로직을 거쳐야 하는 메시지"의 주소 체계이며, 게임 이동처럼 검증 및 상태 변경이 필요한 건 전부 이쪽
         */
        registry.setApplicationDestinationPrefixes("/app"); // 들어가는 문

        // convertAndSendToUser() 시 붙는 prefix: /user/queue/errors  →  /queue/errors-user{세션ID} 번역기 역할
        // 특정 사용자 한 명에게만 보내는 메시지를 위한 장치
        registry.setUserDestinationPrefix("/user");
    }

    // CONNECT 프레임의 JWT 검증 인터셉터를 인바운드 채널에 등록
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}