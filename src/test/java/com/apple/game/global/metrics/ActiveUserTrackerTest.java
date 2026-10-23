package com.apple.game.global.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ActiveUserTracker 계약:
 *  - 같은 사용자가 몇 번 활동해도 1명이다
 *  - 창(window) 밖으로 밀려난 사용자는 세지 않는다 — 1분 창과 5분 창이 따로 움직인다
 *  - 게이지 active_users{window} 로 Prometheus에 노출된다
 */
class ActiveUserTrackerTest {

    /** 테스트가 직접 밀어 움직이는 시계 */
    static class StepClock extends Clock {
        private Instant now = Instant.parse("2026-10-23T04:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    final StepClock clock = new StepClock();
    final ActiveUserTracker tracker = new ActiveUserTracker(clock);

    @Test
    @DisplayName("같은 사용자는 여러 번 활동해도 한 명으로 센다")
    void sameUserCountsOnce() {
        tracker.touch(1L);
        tracker.touch(1L);
        tracker.touch(1L);
        tracker.touch(2L);

        assertThat(tracker.countActiveWithin(Duration.ofMinutes(5))).isEqualTo(2);
    }

    @Test
    @DisplayName("창 밖으로 밀려난 사용자는 빠진다 — 1분 창과 5분 창이 따로 움직인다")
    void windowsExpireIndependently() {
        tracker.touch(1L);                       // t=0
        clock.advance(Duration.ofMinutes(2));
        tracker.touch(2L);                       // t=2m

        assertThat(tracker.countActiveWithin(ActiveUserTracker.SHORT_WINDOW)).as("1분 창: 2번만").isEqualTo(1);
        assertThat(tracker.countActiveWithin(ActiveUserTracker.LONG_WINDOW)).as("5분 창: 둘 다").isEqualTo(2);

        clock.advance(Duration.ofMinutes(3).plusSeconds(1));   // t=5m 1s — 1번은 5분 창 밖

        assertThat(tracker.countActiveWithin(ActiveUserTracker.SHORT_WINDOW)).isZero();
        assertThat(tracker.countActiveWithin(ActiveUserTracker.LONG_WINDOW)).as("5분 창: 2번만").isEqualTo(1);
    }

    @Test
    @DisplayName("다시 활동하면 창 안으로 돌아온다")
    void touchRefreshesLastSeen() {
        tracker.touch(1L);
        clock.advance(Duration.ofMinutes(10));
        assertThat(tracker.countActiveWithin(ActiveUserTracker.LONG_WINDOW)).isZero();

        tracker.touch(1L);
        assertThat(tracker.countActiveWithin(ActiveUserTracker.LONG_WINDOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("userId가 없으면(익명) 세지 않는다")
    void nullUserIgnored() {
        tracker.touch(null);

        assertThat(tracker.countActiveWithin(Duration.ofMinutes(5))).isZero();
    }

    @Test
    @DisplayName("active_users{window=1m|5m} 게이지로 노출된다")
    void exposesGaugesPerWindow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        tracker.bindTo(registry);

        tracker.touch(1L);
        clock.advance(Duration.ofMinutes(2));
        tracker.touch(2L);

        assertThat(registry.get("active.users").tag("window", "1m").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("active.users").tag("window", "5m").gauge().value()).isEqualTo(2.0);
    }
}
