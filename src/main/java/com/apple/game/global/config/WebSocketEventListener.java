package com.apple.game.global.config;

import com.apple.game.domain.room.service.DisconnectService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

// WebSocket 세션 종료 이벤트 — 정상 DISCONNECT 프레임, 탭 닫힘, 네트워크 단절 모두 여기로 온다.
// 이벤트에는 sessionId뿐이라 '누구·어느 방'은 DisconnectService가 Redis 매핑으로 푼다.
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final DisconnectService disconnectService;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        disconnectService.onSessionClosed(event.getSessionId());
    }
}
