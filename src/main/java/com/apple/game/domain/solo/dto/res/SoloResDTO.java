package com.apple.game.domain.solo.dto.res;

import java.time.LocalDateTime;
import java.util.List;

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

    public record RecordPage(
            List<RecordItem> records,
            Long nextCursor,
            boolean hasNext
    ){
    }

    public record RecordItem(
            Long recordId,
            int score,
            int playTimeSeconds,
            LocalDateTime createdAt
    ){
    }

    public record Summary(
            Integer bestScore,
            long totalGames,
            Double averageScore,
            Integer allTimeRank
    ){
    }
}
