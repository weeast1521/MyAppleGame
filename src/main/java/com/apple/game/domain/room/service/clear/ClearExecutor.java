package com.apple.game.domain.room.service.clear;

import java.util.List;

/**
 * [게임 중 검증 + 멤버 검증 + 존재 검증 + 합10 검증 + HDEL + HINCRBY]를 어떻게 원자적으로 묶을지에 대한 전략 인터페이스.
 *
 * 세 구현(NoLock / RedissonLock / Lua)은 입력과 결과가 완전히 같고 "틈을 어떻게 막느냐"만 다르다.
 * AppleClearService는 이 인터페이스만 보고 동작하므로, 전략을 바꿔도 STOMP 핸들러·브로드캐스트 코드는 그대로다.
 * 동시성 테스트(AppleClearConcurrencyTest)도 세 구현을 같은 시나리오로 돌려 결과만 비교한다.
 */
public interface ClearExecutor {

    ClearStrategy strategy();

    /**
     * @param roomCode 방 코드
     * @param userId   요청자 (STOMP Principal에서 꺼낸 값 — 클라이언트가 보낸 값이 아니다)
     * @param fields   선택 영역의 보드 Hash 필드 목록 ("r:c"), 범위 검증은 호출 전에 끝나 있다
     */
    ClearOutcome tryClear(String roomCode, Long userId, List<String> fields);
}
