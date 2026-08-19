package com.apple.game.domain.room.dto.ws;

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
}
