package com.apple.game.domain.room.service;

import com.apple.game.domain.room.dto.ws.ClearRequest;
import com.apple.game.domain.room.dto.ws.GameSocketMessage;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.room.service.clear.ClearExecutor;
import com.apple.game.domain.room.service.clear.ClearOutcome;
import com.apple.game.domain.room.service.clear.ClearStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * /app/room/{code}/clear 의 흐름 전체를 담당한다.
 *
 *   ① 범위 검증 (Redis 불필요)            → 실패: CLEAR_REJECTED(INVALID_RANGE)
 *   ② requestId 멱등 검사 (SADD)          → 중복: 조용히 무시 (첫 처리 결과는 이미 전달됐다)
 *   ③ ClearExecutor.tryClear (전략별 원자 실행) → 실패: CLEAR_REJECTED(reason)
 *   ④ 성공: 이번 판 점수 조회 → APPLES_CLEARED 브로드캐스트 (방 전원)
 *
 * 동시성의 본체는 ③ 하나뿐이고, 나머지는 순서에 민감하지 않다.
 * 어떤 Executor를 쓸지는 application.yaml의 game.clear.strategy 로 정한다 (기본 LUA).
 *
 * 메시지 경로:
 *   성공 → /topic/room/{code}                 (convertAndSend: 구독자 전원)
 *   실패 → /user/queue/errors                 (convertAndSendToUser: userId 이름의 Principal 세션에만)
 *          WebSocketConfig의 setUserDestinationPrefix("/user")가 이 주소를 세션별 큐로 번역한다.
 */
@Slf4j
@Service
public class AppleClearService {

    private final RoomRedisRepository roomRedisRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ClearExecutor executor;

    public AppleClearService(RoomRedisRepository roomRedisRepository,
                             SimpMessagingTemplate messagingTemplate,
                             List<ClearExecutor> executors,
                             @Value("${game.clear.strategy:LUA}") ClearStrategy strategy) {
        this.roomRedisRepository = roomRedisRepository;
        this.messagingTemplate = messagingTemplate;
        this.executor = executors.stream()
                .filter(e -> e.strategy() == strategy)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("등록되지 않은 clear 전략: " + strategy));
        log.info("사과 제거 전략: {} ({})", strategy, executor.getClass().getSimpleName());
    }

    public void clear(String roomCode, Long userId, ClearRequest request) {
        // ① 범위 검증 — 보드 밖 좌표는 Redis까지 갈 이유가 없다
        if (request.requestId() == null || request.requestId().isBlank() || !request.isValidRange()) {
            reject(userId, request.requestId(), ClearOutcome.Status.INVALID_RANGE.name());
            return;
        }

        // ② 멱등 — 재전송된 같은 requestId는 두 번째부터 버린다.
        //    (Executor 호출 전에 표시하므로, 표시 직후 Redis가 죽어 실패하면 그 requestId는 재시도해도 무시된다 — 허용 가능한 트레이드오프)
        if (!roomRedisRepository.markRequestOnce(roomCode, request.requestId())) {
            log.debug("중복 clear 요청 무시: roomCode={}, userId={}, requestId={}", roomCode, userId, request.requestId());
            return;
        }

        // ③ 원자 실행
        ClearOutcome outcome = executor.tryClear(roomCode, userId, request.fields());
        if (!outcome.isSuccess()) {
            log.debug("clear 거절: roomCode={}, userId={}, status={}", roomCode, userId, outcome.status());
            reject(userId, request.requestId(), outcome.status().name());
            return;
        }

        // ④ 브로드캐스트 — cells는 실제로 지워진 칸만, scores는 이번 판 전원의 점수
        List<GameSocketMessage.ApplesCleared.Cell> cells = outcome.clearedFields().stream()
                .map(GameSocketMessage.ApplesCleared.Cell::fromField)
                .toList();
        Map<Long, Integer> scores = roomRedisRepository.findScores(roomCode);

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomCode,
                GameSocketMessage.ApplesCleared.of(userId, cells, scores));

        log.debug("APPLES_CLEARED: roomCode={}, userId={}, gained={}", roomCode, userId, outcome.gained());
    }

    private void reject(Long userId, String requestId, String reason) {
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId), // Principal.getName() — StompAuthChannelInterceptor가 심은 userId
                "/queue/errors",
                GameSocketMessage.ClearRejected.of(requestId, reason));
    }
}
