package com.apple.game.global.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "지금 접속해 있는 사람이 몇 명인가"를 고유 사용자 수로 센다 — Prometheus 게이지 active_users{window="1m|5m"}.
 *
 * 기존 지표로는 이 질문에 답할 수 없었다.
 *  - websocket_sessions 는 대전 방에 들어온 사람만 센다(프론트가 방 입장 때만 WebSocket을 연다).
 *  - http_server_requests 로 솔로 시작 횟수를 세면 보드 재시작이 중복으로 잡히고, 랭킹만 보는 사람은 빠진다.
 * 그래서 인증을 통과한 지점(JWT 필터, STOMP 인터셉터)에서 userId와 마지막 활동 시각을 기록하고,
 * 게이지는 "최근 N분 안에 활동한 userId 수"를 돌려준다. 솔로·대전·홈 화면 사용자가 모두 한 번씩만 잡힌다.
 *
 * 인메모리·인스턴스 단위다. blue/green은 한 색만 트래픽을 받으므로 지금은 합산이 곧 전체다.
 * 인스턴스를 수평 확장하면 Redis로 옮겨야 한다(그때는 sum() 대신 한 인스턴스 값을 쓰거나 키를 공유).
 */
@Component
public class ActiveUserTracker implements MeterBinder {

    static final Duration SHORT_WINDOW = Duration.ofMinutes(1);
    static final Duration LONG_WINDOW = Duration.ofMinutes(5);

    // 이보다 오래 조용한 사용자는 맵에서 치운다. 게이지 창(5분)보다 넉넉히 두어
    // 창 경계 근처의 사용자가 지워졌다 다시 생기며 수치가 흔들리는 일을 막는다.
    private static final Duration RETENTION = Duration.ofMinutes(30);

    private final Clock clock;
    private final Map<Long, Long> lastSeenMillis = new ConcurrentHashMap<>();

    public ActiveUserTracker() {
        this(Clock.systemUTC());
    }

    // 테스트에서 시계를 움직이기 위한 생성자
    ActiveUserTracker(Clock clock) {
        this.clock = clock;
    }

    /** 인증된 사용자의 활동을 기록한다. 매 요청마다 불리므로 맵 put 한 번 이상은 하지 않는다. */
    public void touch(Long userId) {
        if (userId == null) {
            return;
        }
        lastSeenMillis.put(userId, clock.millis());
    }

    /** 최근 window 안에 한 번이라도 활동한 고유 사용자 수 */
    public int countActiveWithin(Duration window) {
        long now = clock.millis();
        evictOlderThan(now - RETENTION.toMillis());

        long cutoff = now - window.toMillis();
        int count = 0;
        for (long seen : lastSeenMillis.values()) {
            if (seen >= cutoff) {
                count++;
            }
        }
        return count;
    }

    // 스케줄러를 따로 두지 않고 게이지를 읽을 때(15초마다) 정리한다 — 사용자 수가 수천이어도 순회 비용은 무시할 수준.
    private void evictOlderThan(long cutoff) {
        lastSeenMillis.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // 1m: "지금 이 순간" 에 가까운 값. 5m: 솔로 한 판(120초)과 메뉴 이동을 포함해 '접속 중'으로 볼 만한 창.
        registerGauge(registry, "1m", SHORT_WINDOW);
        registerGauge(registry, "5m", LONG_WINDOW);
    }

    private void registerGauge(MeterRegistry registry, String windowTag, Duration window) {
        Gauge.builder("active.users", this, tracker -> tracker.countActiveWithin(window))
                .tag("window", windowTag)
                .description("최근 window 안에 인증된 HTTP 요청 또는 STOMP 프레임을 보낸 고유 사용자 수")
                .register(registry);
    }
}
