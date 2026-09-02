package com.apple.game.domain.room.service;

import com.apple.game.domain.match.entity.GameMatch;
import com.apple.game.domain.match.repository.GameMatchRepository;
import com.apple.game.domain.room.dto.ws.GameSocketMessage;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.solo.game.BoardGenerator;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.exception.UserErrorCode;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameStartService {

    private static final int TIME_LIMIT_SECONDS = 120;

    private final SecureRandom secureRandom = new SecureRandom();

    private final RoomRedisRepository roomRedisRepository;
    private final GameMatchRepository gameMatchRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final GameEndService gameEndService;
    private final GameTimerRegistry timerRegistry;

    @Transactional
    public void ready(String roomCode, Long userId) {
        String result = roomRedisRepository.readyAtomic(roomCode, userId);

        // START가 아니면 전부 조용히 무시:
        // WAIT = 상대 대기, ALREADY_PLAYING = 게임 중 재접속이 보낸 ready(새 판 시작 방지),
        // NOT_FOUND/NOT_MEMBER = 잘못된 요청
        if (!result.startsWith("START:")) {
            log.debug("ready 무시 : roomCode={}, userId={}, result={}", roomCode, userId, result);
            return ;
        }
        int round = Integer.parseInt(result.substring("START:".length()));

        // 시드 생성 → game_match INSERT → 같은 시드로 보드 생성 → Redis 저장
        long seed = secureRandom.nextLong();
        GameMatch match = gameMatchRepository.save(GameMatch.start(roomCode, String.valueOf(seed)));

        int[][] board = BoardGenerator.generate(seed);
        roomRedisRepository.resetRoundKeys(roomCode); // 이전 판의 scores·requestId 정리 (연전 시 점수가 이월되지 않게)
        roomRedisRepository.saveBoard(roomCode, board);
        Instant startedAt = Instant.now();
        roomRedisRepository.markStarted(roomCode, match.getId(), startedAt.toEpochMilli()); // 재접속 스냅샷·몰수 정산이 판을 찾는 근거

        // Lua가 START를 반환했다면 방 상태는 이미 PLAYING — host/guest가 모두 존재한다
        Map<Object, Object> room = roomRedisRepository.findRoom(roomCode);
        Long hostId = Long.valueOf((String) room.get("hostId"));
        Long guestId = Long.valueOf((String) room.get("guestId"));

        Map<Long, String> players = new LinkedHashMap<>();
        players.put(hostId, findNickname(hostId));
        players.put(guestId, findNickname(guestId));

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomCode,
                GameSocketMessage.GameStart.of(
                        match.getId(), round, players, board,
                        TIME_LIMIT_SECONDS, startedAt.toString()));

        // 판 종료 타이머 — 제한시간 + 1초 유예(마지막 순간의 clear가 도착할 시간).
        // 판정은 endByTimeUp이 다시 하므로(status·@Version) 타이머가 이르든 늦든 정합성엔 영향 없다.
        // 등록부(registry)에 두는 이유: 재접속 시 '이 인스턴스가 아는 판인가'를 has()로 판별한다.
        Long matchId = match.getId();
        timerRegistry.schedule(
                GameTimerRegistry.matchKey(matchId),
                startedAt.plusSeconds(TIME_LIMIT_SECONDS + 1),
                () -> gameEndService.endByTimeUp(matchId, roomCode));

        log.info("GAME_START: roomCode={}, matchId={}, round={}", roomCode, match.getId(), round);
    }

    private String findNickname(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.NOT_FOUND));
        return user.getNickname();
    }
}
