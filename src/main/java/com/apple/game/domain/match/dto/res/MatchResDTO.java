package com.apple.game.domain.match.dto.res;

import java.time.LocalDateTime;
import java.util.List;

public class MatchResDTO {

    // GET /api/matches/me 응답 — 프론트(app.js Records.loadMatches)가 기대하는 형태
    public record MyMatches(
            Summary summary,
            List<MatchRow> matches,
            Long nextCursor,
            boolean hasNext
    ) {}

    // 몰수승/패(FORFEIT_*)는 승/패에 합산해 집계한다
    public record Summary(long totalMatches, long wins, long losses, long draws) {}

    public record MatchRow(
            Long matchId,
            String result,          // WIN / LOSE / DRAW / FORFEIT_WIN / FORFEIT_LOSE
            int myScore,
            String opponentNickname,
            int opponentScore,
            LocalDateTime finishedAt
    ) {}
}
