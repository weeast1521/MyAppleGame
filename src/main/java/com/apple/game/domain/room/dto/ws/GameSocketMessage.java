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
        public record Cell(int r, int c) {
            // 보드 Hash 필드("r:c") → 프론트가 기대하는 {r, c}
            public static Cell fromField(String field) {
                int sep = field.indexOf(':');
                return new Cell(Integer.parseInt(field.substring(0, sep)), Integer.parseInt(field.substring(sep + 1)));
            }
        }

        public static ApplesCleared of(Long clearedBy, List<Cell> cells, Map<Long, Integer> scores) {
            return new ApplesCleared("APPLES_CLEARED", clearedBy, cells, scores);
        }
    }

    public record ClearRejected(String type, String requestId, String reason) {
        public static ClearRejected of(String requestId, String reason) {
            return new ClearRejected("CLEAR_REJECTED", requestId, reason);
        }
    }

    // 판 종료 — reason: TIME_UP(타이머) / OPPONENT_LEFT(Step 12 몰수) / ABORTED(중단).
    // scores = 이번 판, totalScores = 방 단위 누적(연전), results = userId → WIN/LOSE/DRAW…
    // winnerUserId는 무승부면 null. (Long 키는 JSON에서 문자열이 된다 — 프론트도 문자열로 비교)
    public record GameEnd(
            String type,
            Long matchId,
            int round,
            String reason,
            Map<Long, Integer> scores,
            Map<Long, Integer> totalScores,
            Map<Long, String> results,
            Long winnerUserId
    ) {
        public static GameEnd of(Long matchId, int round, String reason,
                                 Map<Long, Integer> scores, Map<Long, Integer> totalScores,
                                 Map<Long, String> results, Long winnerUserId) {
            return new GameEnd("GAME_END", matchId, round, reason, scores, totalScores, results, winnerUserId);
        }
    }

    // 상대가 방을 나감 — 프론트는 누적 점수를 초기화하고 새 상대 대기 화면으로 돌아간다
    public record PlayerLeft(String type, Long userId) {
        public static PlayerLeft of(Long userId) {
            return new PlayerLeft("PLAYER_LEFT", userId);
        }
    }
}
