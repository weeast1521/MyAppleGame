package com.apple.game.domain.match.service;

import com.apple.game.domain.match.dto.res.MatchResDTO;
import com.apple.game.domain.match.entity.MatchPlayer;
import com.apple.game.domain.match.entity.MatchResult;
import com.apple.game.domain.match.repository.MatchPlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MatchQueryService {

    private static final int MAX_PAGE_SIZE = 50;

    private final MatchPlayerRepository matchPlayerRepository;

    /**
     * 내 대전 전적 (커서 페이지네이션).
     * 쿼리 3개로 고정: ① 내 행 페이지(match FETCH) ② 상대 행 IN 묶음(user FETCH) ③ 결과별 집계.
     * 페이지 크기와 무관하게 3회 — 행마다 상대를 찾는 N+1이 없다.
     */
    @Transactional(readOnly = true)
    public MatchResDTO.MyMatches getMyMatches(Long userId, Long cursor, Integer size) {
        int pageSize = (size == null || size < 1) ? 20 : Math.min(size, MAX_PAGE_SIZE);
        long effectiveCursor = (cursor == null) ? Long.MAX_VALUE : cursor;

        Slice<MatchPlayer> slice =
                matchPlayerRepository.findPageByUserId(userId, effectiveCursor, PageRequest.of(0, pageSize));
        List<MatchPlayer> mine = slice.getContent();

        // 같은 판의 상대 행을 matchId로 묶어 한 번에
        List<Long> matchIds = mine.stream().map(mp -> mp.getMatch().getId()).toList();
        Map<Long, MatchPlayer> opponentByMatch = matchIds.isEmpty()
                ? Map.of()
                : matchPlayerRepository.findOpponents(matchIds, userId).stream()
                        .collect(Collectors.toMap(mp -> mp.getMatch().getId(), Function.identity()));

        List<MatchResDTO.MatchRow> rows = mine.stream()
                .map(mp -> {
                    MatchPlayer opp = opponentByMatch.get(mp.getMatch().getId());
                    return new MatchResDTO.MatchRow(
                            mp.getMatch().getId(),
                            mp.getResult().name(),
                            mp.getScore(),
                            opp != null ? opp.getUser().getNickname() : "(알 수 없음)",
                            opp != null ? opp.getScore() : 0,
                            mp.getMatch().getFinishedAt());
                })
                .toList();

        Long nextCursor = slice.hasNext() ? mine.get(mine.size() - 1).getId() : null;

        return new MatchResDTO.MyMatches(buildSummary(userId), rows, nextCursor, slice.hasNext());
    }

    private MatchResDTO.Summary buildSummary(Long userId) {
        Map<MatchResult, Long> counts = new EnumMap<>(MatchResult.class);
        for (Object[] row : matchPlayerRepository.countByResult(userId)) {
            counts.put((MatchResult) row[0], (Long) row[1]);
        }
        long wins = counts.getOrDefault(MatchResult.WIN, 0L) + counts.getOrDefault(MatchResult.FORFEIT_WIN, 0L);
        long losses = counts.getOrDefault(MatchResult.LOSE, 0L) + counts.getOrDefault(MatchResult.FORFEIT_LOSE, 0L);
        long draws = counts.getOrDefault(MatchResult.DRAW, 0L);
        return new MatchResDTO.Summary(wins + losses + draws, wins, losses, draws);
    }
}
