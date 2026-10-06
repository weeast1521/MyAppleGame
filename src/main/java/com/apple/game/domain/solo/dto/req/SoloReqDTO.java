package com.apple.game.domain.solo.dto.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class SoloReqDTO {

    // 보드 10×17 = 170칸, 한 move 는 합 10 이라 최소 2칸을 지운다 → 한 판의 move 는 85개를 넘을 수 없다.
    // 상한이 없으면 요청 1건에 move 수백만 개를 실어 보드 재생(finish)에 CPU 를 쓰게 할 수 있다(#58)
    private static final int MAX_MOVES = 85;

    // POST /api/solo/games/{gameSessionId}/finish
    public record Finish(
            @NotNull @Valid
            @Size(max = MAX_MOVES, message = "move 가 너무 많습니다.")
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
