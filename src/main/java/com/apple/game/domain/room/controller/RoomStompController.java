package com.apple.game.domain.room.controller;

import com.apple.game.domain.room.service.GameStartService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class RoomStompController {

    private final GameStartService gameStartService;

    // 프론트 발행: /app/room/{roomCode}/ready (연결 직후 + 재대결 버튼)
    // principal은 CONNECT 때 인터셉터가 심어둔 것 — getName() == userId
    @MessageMapping("/room/{roomCode}/ready")
    public void ready(@DestinationVariable String roomCode, Principal principal) {
        gameStartService.ready(roomCode, Long.valueOf(principal.getName()));
    }
}
