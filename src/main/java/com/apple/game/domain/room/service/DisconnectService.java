package com.apple.game.domain.room.service;

import com.apple.game.domain.room.dto.ws.GameSocketMessage;
import com.apple.game.domain.room.entity.RoomStatus;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * WebSocket 끊김 → 유예 → 몰수/퇴장.
 *
 * "끊김"은 의도를 말해주지 않는다(창 닫음 / 새로고침 / 네트워크 순단이 구분되지 않는다).
 * 그래서 끊기자마자 판정하지 않고 GRACE_SECONDS 동안 재접속을 기다린다.
 *
 * 타이머 취소 경합: 유예 타이머는 실행 시점에 '내가 표시한 nonce가 아직 현재값인가'로 판정한다.
 * 재접속(clearDisconnected)이 먼저면 nonce가 없어 물러나고, 재접속 후 다시 끊겨 새 nonce가 생겼으면
 * 옛 타이머는 불일치로 물러난다. registry.cancel()은 최적화일 뿐 정합성의 근거가 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisconnectService {

    public static final int GRACE_SECONDS = 15;

    private final RoomRedisRepository roomRedisRepository;
    private final GameTimerRegistry timerRegistry;
    private final GameEndService gameEndService;
    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    /** SessionDisconnectEvent 진입점 */
    public void onSessionClosed(String sessionId) {
        Map<Object, Object> session = roomRedisRepository.findSession(sessionId);
        roomRedisRepository.unbindSession(sessionId);
        if (session.isEmpty()) {
            return; // 방에 묶인 적 없는 세션(로비만 보다 닫음 등)
        }
        Long userId = Long.valueOf((String) session.get("userId"));
        String roomCode = (String) session.get("roomCode");

        // 이미 다른 세션으로 재접속해 있으면(새로고침: 새 CONNECT가 먼저) 옛 세션의 종료는 이탈이 아니다
        if (!roomRedisRepository.isCurrentSession(roomCode, userId, sessionId)) {
            log.debug("옛 세션 종료 무시(이미 재접속): roomCode={}, userId={}", roomCode, userId);
            return;
        }

        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        String me = String.valueOf(userId);
        if (room.isEmpty() || (!me.equals(room.get("hostId")) && !me.equals(room.get("guestId")))) {
            return; // 방이 이미 없거나(유령 세션) 멤버가 아님
        }

        String nonce = roomRedisRepository.markDisconnected(roomCode, userId);
        boolean playing = RoomStatus.PLAYING.name().equals(room.get("status"));
        if (playing) {
            messagingTemplate.convertAndSend("/topic/room/" + roomCode,
                    GameSocketMessage.OpponentStatus.disconnected(userId, GRACE_SECONDS));
        }
        timerRegistry.schedule(
                GameTimerRegistry.graceKey(roomCode, userId),
                Instant.now().plusSeconds(GRACE_SECONDS),
                () -> onGraceExpired(roomCode, userId, nonce));
        log.info("연결 끊김 — 유예 {}초: roomCode={}, userId={}, playing={}", GRACE_SECONDS, roomCode, userId, playing);
    }

    /** 유예 타이머 본체. nonce가 현재값일 때만 이탈로 확정한다. */
    public void onGraceExpired(String roomCode, Long userId, String nonce) {
        if (!roomRedisRepository.isDisconnectNonceCurrent(roomCode, userId, nonce)) {
            log.debug("유예 만료 무시(재접속 또는 새 이탈로 대체): roomCode={}, userId={}", roomCode, userId);
            return;
        }
        roomRedisRepository.clearDisconnected(roomCode, userId);

        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        if (RoomStatus.PLAYING.name().equals(room.get("status"))) {
            gameEndService.endByForfeit(roomCode, userId); // GAME_END(OPPONENT_LEFT) + FORFEIT 정산 + finishRound
        }
        roomService.leave(userId, roomCode); // 방에서 제거 + 남은 사람에게 PLAYER_LEFT (승수·round 초기화)
        log.info("이탈 확정(유예 초과): roomCode={}, userId={}", roomCode, userId);
    }
}
