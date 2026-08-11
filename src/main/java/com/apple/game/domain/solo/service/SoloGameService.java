package com.apple.game.domain.solo.service;

import com.apple.game.domain.solo.dto.req.SoloReqDTO;
import com.apple.game.domain.solo.dto.res.SoloResDTO;
import com.apple.game.domain.solo.entity.SoloRecord;
import com.apple.game.domain.solo.exception.SoloErrorCode;
import com.apple.game.domain.solo.game.GameBoard;
import com.apple.game.domain.solo.repository.SoloRecordRepository;
import com.apple.game.domain.solo.session.SoloGameRepository;
import com.apple.game.domain.solo.session.SoloGameSession;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.exception.UserErrorCode;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class SoloGameService {

    private static final int TIME_LIMIT_SECONDS = 120;
    private static final int SESSION_TTL_SECONDS = TIME_LIMIT_SECONDS + 30;

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final SoloGameRepository soloGameRepository;
    private final SoloRecordRepository soloRecordRepository;
    private final UserRepository userRepository;

    public SoloResDTO.Start start(Long userId) {
        String gameSessionId = UUID.randomUUID().toString();
        long boardSeed = ThreadLocalRandom.current().nextLong();

        GameBoard board = GameBoard.fromSeed(boardSeed);

        SoloGameSession session = SoloGameSession.create(
                gameSessionId, userId, boardSeed, System.currentTimeMillis());

        soloGameRepository.save(session, Duration.ofSeconds(SESSION_TTL_SECONDS));

        return new SoloResDTO.Start(gameSessionId, String.valueOf(boardSeed), board.snapshot(), TIME_LIMIT_SECONDS);
    }

    @Transactional
    public SoloResDTO.Finish finish(Long userId, String gameSessionId, SoloReqDTO.Finish request) {
        // 1. 세션 조회 — 없거나 TTL 만료면 SOLO404
        SoloGameSession session = soloGameRepository.find(gameSessionId)
                .orElseThrow(() -> new CustomException(SoloErrorCode.SESSION_NOT_FOUND));

        // 2. 본인 세션 검증 — 남의 세션이면 존재 여부를 숨기기 위해 404로 응답
        if (!session.getUserId().equals(userId)) {
            throw new CustomException(SoloErrorCode.SESSION_NOT_FOUND);
        }

        // 3. 세션 삭제를 저장보다 먼저 — delete()가 false면 다른 요청이 먼저 정산한 것 (SOLO409)
        //    메모: 두 요청이 동시에 1~2를 통과할 수 있음 → Step 10에서 원자성으로 해결
        if (!soloGameRepository.delete(gameSessionId)) {
            throw new CustomException(SoloErrorCode.ALREADY_SUBMITTED);
        }

        // 4~5. 시드로 최초 보드 재구성 → moves를 순서대로 재생하며 서버가 재검증
        GameBoard board = GameBoard.fromSeed(session.getBoardSeed());

        int score = 0;
        int clearedCount = 0;
        for (SoloReqDTO.Move move : request.moves()) {
            int removed = board.clear(move.r1(), move.c1(), move.r2(), move.c2());
            if (removed == -1) {
                throw new CustomException(SoloErrorCode.INVALID_MOVES);
            }
            score += removed;
            clearedCount++;
        }

        // 6. 플레이 시간 — 서버 시계 기준, 제한시간을 상한으로
        int playTimeSeconds = (int) Math.min(
                (System.currentTimeMillis() - session.getStartedAtMills()) / 1000,
                TIME_LIMIT_SECONDS);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.NOT_FOUND));

        // 7. isPersonalBest는 INSERT "전에" 판정 — 저장 후 조회하면 방금 넣은 기록과 비교하게 된다
        int previousBest = soloRecordRepository.findTopByUserIdOrderByScoreDesc(userId)
                .map(SoloRecord::getScore)
                .orElse(-1); // 첫 게임이면 무조건 갱신
        boolean isPersonalBest = score > previousBest;

        SoloRecord record = soloRecordRepository.save(SoloRecord.create(
                user, score, clearedCount, playTimeSeconds, String.valueOf(session.getBoardSeed())));

        int allTimeRank = (int) soloRecordRepository.countUsersWithScoreAbove(score) + 1;

        return new SoloResDTO.Finish(record.getId(), score, isPersonalBest, allTimeRank);
    }

    @Transactional(readOnly = true)
    public SoloResDTO.RecordPage getMyRecords(Long userId, Long cursor, Integer size) {
        int pageSize = normalizeSize(size);

        long effectiveCursor = (cursor == null) ? Long.MAX_VALUE : cursor;

        Slice<SoloRecord> slice =
                soloRecordRepository.findPageByUserId(userId, effectiveCursor, PageRequest.of(0, pageSize));

        List<SoloRecord> page = slice.getContent();
        Long nextCursor = slice.hasNext() ? page.get(page.size() - 1).getId() : null;

        List<SoloResDTO.RecordItem> records = page.stream()
                .map(r -> new SoloResDTO.RecordItem(
                        r.getId(), r.getScore(), r.getPlayTimeSeconds(), r.getCreatedAt()))
                .toList();

        return new SoloResDTO.RecordPage(records, nextCursor, slice.hasNext());
    }

    @Transactional(readOnly = true)
    public SoloResDTO.Summary getSummary(Long userId) {
        SoloRecordRepository.SummaryProjection agg = soloRecordRepository.aggregateByUserId(userId);

        if (agg.getTotalGames() == 0) {
            return new SoloResDTO.Summary(null, 0, null, null);
        }

        int allTimeRank = (int) soloRecordRepository.countUsersWithScoreAbove(agg.getBestScore()) + 1;
        double averageScore = Math.round(agg.getAverageScore() * 10) / 10.0;

        return new SoloResDTO.Summary(agg.getBestScore(), agg.getTotalGames(), averageScore, allTimeRank);
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) return DEFAULT_SIZE;
        return Math.min(size, MAX_SIZE);
    }
}
