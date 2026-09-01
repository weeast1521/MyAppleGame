package com.apple.game.domain.room.service.clear;

/**
 * 사과 제거의 원자성 확보 전략. application.yaml의 game.clear.strategy 로 고른다.
 * 세 구현을 모두 남겨두는 이유: Step 10의 목표가 "무방비 → 분산 락 → Lua"를 직접 비교하는 것이기 때문.
 */
public enum ClearStrategy {
    NO_LOCK,        // 1차: HGETALL → HMGET → HDEL → HINCRBY 를 각각 호출. check와 act 사이에 틈이 있어 중복 득점 버그 발생
    REDISSON_LOCK,  // 2차: 방 단위 Redisson RLock 으로 1차 로직을 감싼다. 정합성은 확보되지만 락 획득/해제 왕복이 추가된다
    LUA             // 3차: [검증 + HDEL + HINCRBY]를 Lua 스크립트 하나로 실행. Redis 단일 스레드가 원자성을 보장 ← 최종 채택
}
