package com.apple.game.global.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import java.util.function.ToIntFunction;

// WebSocket 계층의 상태를 Prometheus로 노출한다 (#32).
// Spring은 이 값들을 WebSocketMessageBrokerStats가 30분마다 로그 한 줄로만 찍는다 — 부하 중에는 쓸모가 없다.
// Micrometer 자동 계측(HTTP·JVM·Hikari)은 STOMP를 모르므로 직접 게이지로 연결한다.
//
// 게이지는 값을 저장하지 않고 Prometheus가 긁어갈 때마다 람다를 호출해 그 순간 값을 읽는다.
// 그래서 세션 수를 따로 세는 카운터(연결 +1 / 끊김 -1)를 두지 않는다 — 원본(Spring 내부 통계)을 그대로 읽는 쪽이
// 이벤트 유실·중복으로 어긋날 일이 없다.
@Component
public class WebSocketMetrics implements MeterBinder {

    private final WebSocketMessageBrokerStats stats;

    // STOMP 인바운드 채널 스레드 풀 — 클라이언트 SEND 프레임(사과 제거 등)이 @MessageMapping에 닿기 전에 줄 서는 곳.
    // Step 15 S4의 "첫 응답 600ms"가 이 큐 대기였다. 그때는 추정이었고, 이 게이지로 확인할 수 있다.
    private final ThreadPoolTaskExecutor inboundExecutor;

    // @RequiredArgsConstructor를 쓰지 않는 이유: 필드의 @Qualifier가 생성자 파라미터로 복사되지 않는다(lombok.config 없음).
    // ThreadPoolTaskExecutor 빈이 inbound·outbound·broker 3개라 이름으로 골라야 한다.
    public WebSocketMetrics(WebSocketMessageBrokerStats stats,
                            @Qualifier("clientInboundChannelExecutor") ThreadPoolTaskExecutor inboundExecutor) {
        this.stats = stats;
        this.inboundExecutor = inboundExecutor;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // transport 태그: SockJS가 WebSocket을 못 쓰는 환경에서 HTTP 스트리밍/폴링으로 폴백한 세션을 구분한다.
        sessionGauge(registry, "websocket", SubProtocolWebSocketHandler.Stats::getWebSocketSessions);
        sessionGauge(registry, "http_streaming", SubProtocolWebSocketHandler.Stats::getHttpStreamingSessions);
        sessionGauge(registry, "http_polling", SubProtocolWebSocketHandler.Stats::getHttpPollingSessions);

        Gauge.builder("websocket.inbound.queued", inboundExecutor, ThreadPoolTaskExecutor::getQueueSize)
                .description("STOMP 인바운드 채널에서 처리를 기다리는 메시지 수")
                .register(registry);
        Gauge.builder("websocket.inbound.active", inboundExecutor, ThreadPoolTaskExecutor::getActiveCount)
                .description("STOMP 인바운드 메시지를 처리 중인 스레드 수")
                .register(registry);
    }

    private void sessionGauge(MeterRegistry registry, String transport,
                              ToIntFunction<SubProtocolWebSocketHandler.Stats> reader) {
        Gauge.builder("websocket.sessions", stats, s -> {
                    // 핸들러 초기화 전(SmartInitializingSingleton 이전)에는 null — 그 사이 수집은 값 없음으로
                    SubProtocolWebSocketHandler.Stats sessionStats = s.getWebSocketSessionStats();
                    return sessionStats == null ? Double.NaN : reader.applyAsInt(sessionStats);
                })
                .tag("transport", transport)
                .description("현재 열려 있는 WebSocket(STOMP) 세션 수")
                .register(registry);
    }
}
