package com.apple.game.domain.room.service.clear;

import java.util.List;

/**
 * ClearExecutor의 실행 결과. SUCCESS일 때만 clearedFields가 채워진다 (실제로 지워진 "r:c" — 빈 칸은 제외).
 * 점수는 지운 사과 개수와 같다 (솔로 모드 SoloGameService와 같은 규칙).
 */
public record ClearOutcome(Status status, List<String> clearedFields) {

    public enum Status {
        SUCCESS,
        ALREADY_TAKEN,  // 남은 사과 합이 10 미만 — 상대가 먼저 지워서 내 화면이 낡았다 (경합 패배)
        INVALID_SUM,    // 남은 사과 합이 10 초과 — 프론트가 합10만 보내므로 정상 흐름에선 안 나온다 (버그/조작)
        INVALID_RANGE,  // 보드 밖이거나 r1>r2 같은 잘못된 영역 — Executor 전에 AppleClearService가 걸러낸다
        NOT_MEMBER,     // 방 멤버가 아님
        NOT_PLAYING,    // 게임 중이 아님 — 방이 없거나, 판이 끝난 뒤 늦게 도착한 요청
        LOCK_TIMEOUT    // (2차 Redisson 전용) 락 획득 대기 초과
    }

    public static ClearOutcome success(List<String> clearedFields) {
        return new ClearOutcome(Status.SUCCESS, clearedFields);
    }

    public static ClearOutcome of(Status status) {
        return new ClearOutcome(status, List.of());
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public int gained() {
        return clearedFields.size();
    }
}
