package com.apple.game.domain.room.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 판 종료·이탈 유예 타이머의 인메모리 등록부.
 *
 * 역할 두 가지:
 *  1) 취소 — 재접속 시 유예 타이머, 몰수 시 TIME_UP 타이머를 best-effort로 취소한다.
 *     cancel()은 이미 실행이 시작된 태스크를 멈추지 못하므로 정합성은 항상 상태(nonce·@Version)가 보장하고,
 *     취소는 불필요한 실행을 줄이는 최적화일 뿐이다.
 *  2) 존재 확인 — has(matchKey)가 false인 PLAYING 판은 '이 인스턴스가 타이머를 모르는 판' = 재시작으로
 *     타이머를 잃은 잔재다. GameStartService가 이를 감지해 무효 처리 후 새 판을 시작한다.
 *     (기동 시 일괄 정리를 하지 않는 이유: blue-green 전환 중 새 색이 이전 색에서 진행 중인 판을
 *      잔재로 오인해 죽인다. 요청이 새 색에 도착한 시점 = 이전 색이 내려간 시점이라 지연 판정이 정확하다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameTimerRegistry {

    private final TaskScheduler gameTaskScheduler;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public static String matchKey(Long matchId) { return "match:" + matchId; }
    public static String graceKey(String roomCode, Long userId) { return "grace:" + roomCode + ":" + userId; }

    public void schedule(String key, Instant at, Runnable task) {
        cancel(key); // 같은 키의 이전 타이머는 대체
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        holder[0] = gameTaskScheduler.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("타이머 실행 실패: key={}", key, e);
            } finally {
                futures.remove(key, holder[0]); // 실행이 끝난 타이머만 제거(그 사이 새로 등록된 것은 보존)
            }
        }, at);
        futures.put(key, holder[0]);
    }

    public boolean cancel(String key) {
        ScheduledFuture<?> f = futures.remove(key);
        return f != null && f.cancel(false);
    }

    public boolean has(String key) {
        return futures.containsKey(key);
    }
}
