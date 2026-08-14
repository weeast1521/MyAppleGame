package com.apple.game.domain.room.service;

import com.apple.game.domain.room.dto.res.RoomResDTO;
import com.apple.game.domain.room.entity.RoomStatus;
import com.apple.game.domain.room.exception.RoomErrorCode;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.exception.UserErrorCode;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RoomService {

    private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_CODE_RETRY = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    private final UserRepository userRepository;
    private final RoomRedisRepository roomRedisRepository;

    public RoomResDTO.Create create(Long hostId) {
        for (int i = 0; i < MAX_CODE_RETRY; i++) {
            String roomCode = generateCode();

            if (roomRedisRepository.createIfAbsent(roomCode, hostId)) {
                return new RoomResDTO.Create(roomCode, RoomStatus.WAITING.name());
            }
        }
        throw new CustomException(RoomErrorCode.ROOM_CODE_EXHAUSTED);
    }

    // 1차 구현: check(조회·검사)와 act(쓰기) 사이에 틈이 있다
    // 방 존재여부, 게임중 여부, 자기방 입장
    public RoomResDTO.Join join(Long userId, String roomCode) {
        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        if (room.isEmpty()) throw new CustomException(RoomErrorCode.ROOM_NOT_FOUND);

        String status = (String) room.get("status");
        if (RoomStatus.PLAYING.name().equals(status)) throw new CustomException(RoomErrorCode.ROOM_PLAYING);

        String hostId = (String) room.get("hostId");
        if (String.valueOf(userId).equals(hostId)) throw new CustomException(RoomErrorCode.ROOM_FULL); // 자기 방 입장 방지

        if (room.get("guestId") != null) throw new CustomException(RoomErrorCode.ROOM_FULL);

        roomRedisRepository.setGuestUnsafe(roomCode, userId);

        return new RoomResDTO.Join(
                roomCode,
                RoomStatus.READY.name(),
                toPlayerInfo(Long.valueOf(hostId)),
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

        // 혼자였던 방 -> 삭제 & 남은 사람이 host
        if (guestId == null) {
            roomRedisRepository.deleteRoom(roomCode);
        } else {
            Long remaining = me.equals(hostId) ? Long.valueOf(guestId) : Long.valueOf(hostId);
            roomRedisRepository.resetToWaiting(roomCode, remaining);
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
