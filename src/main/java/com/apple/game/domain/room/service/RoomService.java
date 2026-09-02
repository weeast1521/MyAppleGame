package com.apple.game.domain.room.service;

import com.apple.game.domain.match.service.MatchSettlementService;
import com.apple.game.domain.room.dto.res.RoomResDTO;
import com.apple.game.domain.room.dto.ws.GameSocketMessage;
import com.apple.game.domain.room.entity.RoomStatus;
import com.apple.game.domain.room.exception.RoomErrorCode;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.exception.UserErrorCode;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_CODE_RETRY = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    private final UserRepository userRepository;
    private final RoomRedisRepository roomRedisRepository;
    private final MatchSettlementService matchSettlementService;
    private final SimpMessagingTemplate messagingTemplate;

    public RoomResDTO.Create create(Long hostId) {
        for (int i = 0; i < MAX_CODE_RETRY; i++) {
            String roomCode = generateCode();

            if (roomRedisRepository.createIfAbsent(roomCode, hostId)) {
                return new RoomResDTO.Create(roomCode, RoomStatus.WAITING.name());
            }
        }
        throw new CustomException(RoomErrorCode.ROOM_CODE_EXHAUSTED);
    }

    // 검사 + 입장을 Lua 스크립트 하나로 통합해 check와 act 사이의 틈이 사리짐
    // 방 존재여부, 게임중 여부, 자기방 입장
    public RoomResDTO.Join join(Long userId, String roomCode) {
        String result = roomRedisRepository.joinAtomic(roomCode, userId);

        switch (result) {
            case "OK" -> {}
            case "NOT_FOUND" -> throw new CustomException(RoomErrorCode.ROOM_NOT_FOUND);
            case "PLAYING" -> throw new CustomException(RoomErrorCode.ROOM_PLAYING);
            case "SELF" -> throw new CustomException(RoomErrorCode.ROOM_SELF_JOIN); // host가 자기 방에 join — FULL로 뭉개면 "가득 참"으로 오해한다
            default -> throw new CustomException(RoomErrorCode.ROOM_FULL);
        }

        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        Long hostId = Long.valueOf((String) room.get("hostId"));

        return new RoomResDTO.Join(
                roomCode,
                (String) room.get("status"),
                toPlayerInfo(hostId),
                toPlayerInfo(userId));
    }

    public void leave(Long userId, String roomCode) {
        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        if (room.isEmpty()) return; // 이미 정리된 방

        String me = String.valueOf(userId);
        String hostId = (String) room.get("hostId");
        String guestId = (String) room.get("guestId");

        // 내 방이 아니면 무시
        if (!me.equals(hostId) && !me.equals(guestId)) return;

        // 게임 도중 이탈 → 진행 중이던 판을 무효(ABORTED) 처리해 전적에 남기지 않는다.
        // Redis 정리보다 먼저 — 방을 먼저 되돌리면 그 사이 타이머가 정산해 버릴 수 있다.
        // 타이머와의 경합은 @Version이 판정: 정산이 이미 이겼으면 조용히 물러난다(판은 정상 기록됨).
        if (RoomStatus.PLAYING.name().equals(room.get("status"))) {
            try {
                matchSettlementService.abortActiveMatch(roomCode);
            } catch (OptimisticLockingFailureException e) {
                log.info("판 무효 경합에서 패배 — TIME_UP 정산이 먼저 완료: roomCode={}", roomCode);
            }
        }

        // 혼자였던 방 -> 삭제 & 남은 사람이 host
        if (guestId == null) {
            roomRedisRepository.deleteRoom(roomCode);
        } else {
            Long remaining = me.equals(hostId) ? Long.valueOf(guestId) : Long.valueOf(hostId);
            roomRedisRepository.resetToWaiting(roomCode, remaining); // 누적(totals)·round도 여기서 초기화

            // 남은 사람의 ready를 복원한다. resetToWaiting이 ready SET을 통째로 지우는데(나간 사람 것을
            // 버리려고), 남은 사람은 여전히 연결된 채 대기 중이고 프론트는 ready를 연결 시 한 번만 보낸다.
            // 복원하지 않으면 새 상대가 들어와 ready해도 SCARD가 1이라 영원히 WAIT — 방이 잠긴다.
            roomRedisRepository.readyAtomic(roomCode, remaining);

            // 남은 사람에게 알림 — 프론트는 누적 점수를 초기화하고 새 상대 대기 화면으로
            messagingTemplate.convertAndSend(
                    "/topic/room/" + roomCode,
                    GameSocketMessage.PlayerLeft.of(userId));
        }
    }

    private RoomResDTO.PlayerInfo toPlayerInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.NOT_FOUND));

        return new RoomResDTO.PlayerInfo(user.getId(), user.getNickname());
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);

        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(secureRandom.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
