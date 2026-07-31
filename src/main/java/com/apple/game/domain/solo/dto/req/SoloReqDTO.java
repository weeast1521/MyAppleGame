package com.apple.game.domain.solo.dto.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class SoloReqDTO {

    // POST /api/solo/games/{gameSessionId}/finish
    public record Finish(
            @NotNull @Valid
            List<Move> moves
    ){
    }

    public record Move(
            int r1,
            int c1,
            int r2,
            int c2,
            //시작으로부터 move한 시각 -> 좌표만으로는 무엇을 지웠는지만 체크 가능, 언제 지웠는지는 체크 불가능
            // 만약 모두 동일한 간격 100ms로 진행했다면 봇 의심 가능
            long elapsedMs
    ){
    }
}
