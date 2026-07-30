package com.apple.game.domain.solo.session;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// GenericJackson2JsonRedisSerializer가 내부 기본 ObjectMapper로 역직렬화할 때 기본 생성자 + 세터 경로가 가장 무난
@Getter
@Setter // Jackson 역직렬화용 — 비즈니스 코드에서 세터 호출은 하지 않는다
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SoloGameSession {

    private String gameSessionId;
    private Long userId; // finish 때 본인 세션인지 검증
    private long boardSeed; // 보드 재구성 키
    private long startedAtMills; // 서버 기준 시작 시각 -> play_time_seconds 계산용

    public static SoloGameSession create(String gameSessionId, Long userId, long boardSeed, long startedAtMills) {
        SoloGameSession session = new SoloGameSession();

        session.gameSessionId = gameSessionId;
        session.userId = userId;
        session.boardSeed = boardSeed;
        session.startedAtMills = startedAtMills;

        return session;
    }
}
