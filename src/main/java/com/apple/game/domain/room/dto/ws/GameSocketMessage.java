package com.apple.game.domain.room.dto.ws;

import java.util.List;
import java.util.Map;

public class GameSocketMessage {

    public record GameStart(
            String type,
            Long matchId,
            int round,
            Map<Long, String> players,
            int[][] board,
            int timeLimitSeconds,
            String startAt
    ) {
        public static GameStart of(Long matchId, int round, Map<Long, String> players,
                                   int[][] board, int timeLimitSeconds, String startAt) {
            return new GameStart("GAME_START", matchId, round, players, board, timeLimitSeconds, startAt);
        }
    }

    public record ApplesCleared(
            String type,
            Long clearedBy,
            List<Cell> cells,
            Map<Long, Integer> scores
    ) {
        public record Cell(int r, int c) {}

        public static ApplesCleared of(Long clearedBy, List<Cell> cells, Map<Long, Integer> scores) {
            return new ApplesCleared("APPLES_CLEARED", clearedBy, cells, scores);
        }
    }

    public record ClearRejected(String type, String requestId, String reason) {
        public static ClearRejected of(String requestId, String reason) {
            return new ClearRejected("CLEAR_REJECTED", requestId, reason);
        }
    }
}
