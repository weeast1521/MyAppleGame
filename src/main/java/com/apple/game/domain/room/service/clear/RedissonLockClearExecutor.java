package com.apple.game.domain.room.service.clear;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 2차 — Redisson 분산 락(RLock)으로 방 단위 임계 구역을 만드는 구현.
 *
 * 1차 로직(NoLockClearExecutor)을 한 줄도 바꾸지 않고, 앞뒤를 lock/unlock으로 감싸기만 한다.
 * 같은 방의 clear 요청은 락을 잡은 하나만 실행되므로 check와 act 사이에 아무도 끼어들 수 없다.
 *
 * 비용: 요청마다 락 획득(SET NX PX + 실패 시 pub/sub 대기)과 해제(Lua)가 붙는다.
 * 즉 1차의 4번 왕복이 6번 이상으로 늘고, 경합 시에는 대기 시간까지 더해진다.
 * 서버 인스턴스가 여러 대여도 Redis 하나를 보고 락을 잡으므로 JVM synchronized와 달리 분산 환경에서도 유효하다.
 *
 * 락 파라미터:
 *   waitTime  — 락이 잡혀 있을 때 기다리는 상한. 넘기면 LOCK_TIMEOUT (사과 하나 지우는 데 이 이상 기다리면 이미 늦은 요청)
 *   leaseTime — 락을 잡은 채 죽었을 때 자동 해제되는 시간 (데드락 방지). 정상 흐름은 수 ms 안에 unlock 한다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonLockClearExecutor implements ClearExecutor {

    private static final long WAIT_MILLIS = 500;
    private static final long LEASE_MILLIS = 3_000;

    private final RedissonClient redissonClient;
    private final NoLockClearExecutor delegate;

    @Override
    public ClearStrategy strategy() {
        return ClearStrategy.REDISSON_LOCK;
    }

    @Override
    public ClearOutcome tryClear(String roomCode, Long userId, List<String> fields) {
        RLock lock = redissonClient.getLock(lockKey(roomCode));
        boolean locked = false;
        try {
            locked = lock.tryLock(WAIT_MILLIS, LEASE_MILLIS, TimeUnit.MILLISECONDS);
            if (!locked) {
                log.warn("clear 락 획득 실패: roomCode={}, userId={}", roomCode, userId);
                return ClearOutcome.of(ClearOutcome.Status.LOCK_TIMEOUT);
            }
            return delegate.tryClear(roomCode, userId, fields); // 임계 구역 — 1차 로직 그대로
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ClearOutcome.of(ClearOutcome.Status.LOCK_TIMEOUT);
        } finally {
            // 내가 잡은 락만 푼다 — leaseTime이 지나 이미 풀린 락을 unlock 하면 IllegalMonitorStateException
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public static String lockKey(String roomCode) {
        return "lock:room:" + roomCode + ":clear";
    }
}
