package com.apple.game.domain.room.controller;

import com.apple.game.domain.room.dto.ws.ClearRequest;
import com.apple.game.domain.room.service.AppleClearService;
import com.apple.game.domain.room.service.GameStartService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class RoomStompController {

    private final GameStartService gameStartService;
    private final AppleClearService appleClearService;

    // 프론트 발행: /app/room/{roomCode}/ready (연결 직후 + 재대결 버튼)
    // principal은 CONNECT 때 인터셉터가 심어둔 것 — getName() == userId
    // simpSessionId: 이 프레임을 보낸 WebSocket 세션 — 끊김 감지가 '누가 끊겼나'를 알 수 있게 방에 묶어둔다
    @MessageMapping("/room/{roomCode}/ready")
    public void ready(@DestinationVariable String roomCode, Principal principal,
                      @Header("simpSessionId") String sessionId) {
        gameStartService.ready(roomCode, Long.valueOf(principal.getName()), sessionId);
    }

    // 프론트 발행: /app/room/{roomCode}/clear  body = { requestId, r1, c1, r2, c2 }
    // @Payload: STOMP 프레임 body(JSON)를 MessageConverter가 ClearRequest로 변환.
    // 요청자는 body가 아니라 Principal에서 꺼낸다 — 클라이언트가 userId를 속일 수 없게.
    // 결과는 반환값이 아니라 서비스가 직접 보낸다 (성공: /topic 브로드캐스트, 실패: /user/queue/errors 개인 전송)
    @MessageMapping("/room/{roomCode}/clear")
    public void clear(@DestinationVariable String roomCode, @Payload ClearRequest request, Principal principal) {
        appleClearService.clear(roomCode, Long.valueOf(principal.getName()), request);
    }
}
