package com.apple.game.domain.solo.dto.res;

public class SoloResDTO {

    // POST /api/solo/games
    public record Start(
            String gameSessionId,
            String boardSeed,
            int[][] board,
            int timeLimitSeconds
    ) {
    }

    // POST /api/solo/games/{id}/finish
    public record Finish(
            Long recordId,
            int score,
            boolean isPersonalBest,
            Integer allTimeRank
    ){
    }
}
