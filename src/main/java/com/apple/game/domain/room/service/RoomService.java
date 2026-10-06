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

    /**
     * 방을 만든다. 한 사람은 방 하나만(#58) — 솔로의 "한 사람은 한 판만"(#50)과 같은 불변식이다.
     *
     * 왜 빈도 제한이 아니라 총량 제약인가: "분당 N개"는 천천히 만드는 공격을 통과시킨다. 방 키는 TTL 6시간이고
     * 프로덕션 Redis 는 noeviction 이라, 한 계정이 방을 쌓기만 해도 전 사용자의 쓰기가 실패한다.
     * 포인터 user:{id}:room 으로 "이 유저의 방"을 하나로 묶으면 방 수는 유저 수를 넘지 못한다.
     *
     * 순서: 새 방을 먼저 만들고 → 포인터를 원자 교체하고 → 직전 방을 상태별로 처리한다.
     *   - 직전 방이 없거나 내가 멤버가 아니다(TTL 소멸, 이미 나감) → 낡은 포인터, 무시
     *   - 직전 방에 나 혼자(WAITING)                              → 버려진 방. 서버가 지운다(#50 과 같은 "서버가 정리")
     *   - 직전 방에 상대가 있다(READY/PLAYING)                      → 거절. 새 방을 지우고 포인터를 되돌린다.
     *     자동으로 나가게 하면 상대의 판이 무효가 되므로(leave 의 abort), 되돌릴 수 없는 결정은 사용자가 내린다.
     *     탭을 닫고 돌아온 경우는 이탈 유예 15초 뒤 DisconnectService 가 leave 를 호출해 포인터를 내린다.
     */
    public RoomResDTO.Create create(Long hostId) {
        String roomCode = allocate(hostId);

        roomRedisRepository.swapActiveRoom(hostId, roomCode)
                .filter(previous -> !previous.equals(roomCode))
                .ifPresent(previous -> {
                    if (!releaseIfAbandoned(hostId, previous)) {
                        // 상대가 있는 방 — 새 방을 거두고 포인터를 되돌린다. 포인터가 아직 새 방을 가리킬 때만(CAS):
                        // 그 사이 같은 유저의 다른 요청이 포인터를 또 바꿨다면 그쪽 결정을 존중한다.
                        roomRedisRepository.deleteRoom(roomCode);
                        roomRedisRepository.restoreActiveRoom(hostId, roomCode, previous);
                        throw new CustomException(RoomErrorCode.ROOM_ALREADY_IN);
                    }
                });

        return new RoomResDTO.Create(roomCode, RoomStatus.WAITING.name());
    }

    private String allocate(Long hostId) {
        for (int i = 0; i < MAX_CODE_RETRY; i++) {
            String roomCode = generateCode();

            if (roomRedisRepository.createIfAbsent(roomCode, hostId)) {
                return roomCode;
            }
        }
        throw new CustomException(RoomErrorCode.ROOM_CODE_EXHAUSTED);
    }

    /**
     * 직전 방을 정리할 수 있으면 정리하고 true. 상대가 있어 정리할 수 없으면 false.
     * 혼자 WAITING 인 방만 서버가 지운다 — REST 로만 만들고 WebSocket 을 열지 않은 방은 이탈 유예 경로를 타지
     * 않아 TTL 6시간까지 남는데, 이것이 바로 "방을 쌓는" 경로다.
     */
    private boolean releaseIfAbandoned(Long userId, String previous) {
        Map<Object, Object> old = roomRedisRepository.findRoom(previous);
        String me = String.valueOf(userId);
        boolean member = me.equals(old.get("hostId")) || me.equals(old.get("guestId"));
        if (old.isEmpty() || !member) return true; // 낡은 포인터

        boolean alone = me.equals(old.get("hostId")) && old.get("guestId") == null;
        if (!alone) return false;

        roomRedisRepository.deleteRoom(previous);
        log.info("버려진 방 정리 — userId={} previous={}", userId, previous);
        return true;
    }

    // 검사 + 입장을 Lua 스크립트 하나로 통합해 check와 act 사이의 틈이 사리짐
    // 방 존재여부, 게임중 여부, 정원. 이미 멤버면(새로고침·탭 닫기 후 복귀) 상태 변경 없이 REJOIN —
    // 이후 WebSocket 연결의 ready가 재접속 처리(스냅샷)를 한다.
    public RoomResDTO.Join join(Long userId, String roomCode) {
        String result = roomRedisRepository.joinAtomic(roomCode, userId);

        switch (result) {
            case "OK", "REJOIN" -> {}
            case "NOT_FOUND" -> throw new CustomException(RoomErrorCode.ROOM_NOT_FOUND);
            case "PLAYING" -> throw new CustomException(RoomErrorCode.ROOM_PLAYING);
            default -> throw new CustomException(RoomErrorCode.ROOM_FULL);
        }

        // 입장도 포인터를 옮긴다(#58). 혼자 기다리던 내 방이 있었다면 지운다 — 방을 만들어 두고 남의 방에
        // 들어가는 경로로도 방이 쌓일 수 있기 때문. 상대가 있는 방은 그대로 둔다(이미 입장이 끝났으므로
        // 되돌릴 수 없고, 그 방은 상대의 이탈 유예나 TTL 로 정리된다).
        if ("OK".equals(result)) {
            roomRedisRepository.swapActiveRoom(userId, roomCode)
                    .filter(previous -> !previous.equals(roomCode))
                    .ifPresent(previous -> releaseIfAbandoned(userId, previous));
        }

        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        Long hostId = Long.valueOf((String) room.get("hostId"));
        String guestRaw = (String) room.get("guestId");

        return new RoomResDTO.Join(
                roomCode,
                (String) room.get("status"),
                toPlayerInfo(hostId),
                guestRaw == null ? null : toPlayerInfo(Long.valueOf(guestRaw))); // 호스트 단독 복귀면 guest 없음
    }

    public void leave(Long userId, String roomCode) {
        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        if (room.isEmpty()) return; // 이미 정리된 방

        String me = String.valueOf(userId);
        String hostId = (String) room.get("hostId");
        String guestId = (String) room.get("guestId");

        // 내 방이 아니면 무시
        if (!me.equals(hostId) && !me.equals(guestId)) return;

        // 포인터가 이 방을 가리킬 때만 내린다(#58). 나가는 사이에 다른 방을 만들었다면 그 포인터는 건드리지 않는다.
        roomRedisRepository.clearActiveRoom(userId, roomCode);

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
            roomRedisRepository.resetToWaiting(roomCode, remaining); // 승수(wins)·round도 여기서 초기화

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
