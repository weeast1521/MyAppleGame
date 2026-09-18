package com.apple.game.domain.solo.service;

import com.apple.game.domain.ranking.entity.RankingPeriod;
import com.apple.game.domain.ranking.repository.RankingRedisRepository;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoloGameService {

    private static final int TIME_LIMIT_SECONDS = 120;

    // 제출 창 — "언제까지 제출을 받아줄까"만 결정한다. 제한시간 준수는 validateMoveTimes가
    // 맡으므로 TTL을 넉넉히 줘도 정합성이 약해지지 않는다.
    // 150초(= 제한시간 + 30초)였을 때는 브라우저가 타이머를 멈춘 시간을 견디지 못했다 —
    // 뒤로가기로 페이지가 bfcache에 얼거나, 비활성 탭에서 setInterval이 조여지거나,
    // 화면이 잠기면 클라이언트 제출이 그만큼 밀리는데 TTL은 실시간으로 흐른다(#49).
    private static final int SESSION_TTL_SECONDS = 600;

    // 이 시간을 넘겨 도착한 제출은 '브라우저가 멈췄다 돌아온' 신호다. 거부하지 않고 기록만 남긴다 —
    // #49가 재발하는지, 확대한 제출 창이 충분한지 판단할 근거가 된다.
    private static final long LATE_SUBMIT_LOG_THRESHOLD_MS = 30_000L;

    // move 시각 허용 오차. 클라이언트 타이머가 250ms 간격이라(solo.js) 제한시간 직후에
    // 완료된 드래그가 한 틱 늦게 기록될 수 있다. 정직한 플레이를 오탐하지 않을 만큼만 준다.
    private static final long MOVE_TIME_GRACE_MS = 1_000L;

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final SoloGameRepository soloGameRepository;
    private final SoloRecordRepository soloRecordRepository;
    private final UserRepository userRepository;
    private final RankingRedisRepository rankingRedisRepository;

    /**
     * 새 판을 시작한다. 한 사람은 한 판만 — 이미 진행 중인 판이 있으면 그 판은 즉시 무효가 된다(#50).
     *
     * 정리를 서버가 하는 이유: 클라이언트에게 "이전 판을 취소해 달라"고 맡기면 요청이 실패하거나
     * 브라우저가 죽었을 때 세션이 고아로 남는다. 서버가 시작 시점에 스스로 정리하면
     * 프론트가 아무것도 하지 않아도 불변식이 지켜진다. TTL은 그래도 최후 방어선으로 남긴다.
     */
    public SoloResDTO.Start start(Long userId) {
        String gameSessionId = UUID.randomUUID().toString();
        long boardSeed = ThreadLocalRandom.current().nextLong();

        GameBoard board = GameBoard.fromSeed(boardSeed);

        SoloGameSession session = SoloGameSession.create(
                gameSessionId, userId, boardSeed, System.currentTimeMillis());

        Duration ttl = Duration.ofSeconds(SESSION_TTL_SECONDS);

        // 순서가 중요하다: 새 세션을 먼저 저장하고 포인터를 교체한다.
        // 반대로 하면 포인터는 새 판을 가리키는데 그 세션이 아직 없는 창이 생긴다.
        soloGameRepository.save(session, ttl);

        // 포인터 교체는 원자적이다 — 연타로 두 요청이 겹쳐도 각자 서로 다른 직전 id를 받아
        // 하나씩만 지우므로, 살아남는 세션은 항상 포인터가 가리키는 그 하나다.
        soloGameRepository.swapActiveSession(userId, gameSessionId, ttl)
                .filter(previous -> !previous.equals(gameSessionId))
                .ifPresent(previous -> {
                    soloGameRepository.delete(previous);
                    log.debug("이전 솔로 세션 무효화 — userId={} previous={} new={}", userId, previous, gameSessionId);
                });

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

        // 판이 끝났으니 활성 세션 포인터도 내린다. 이 세션을 가리킬 때만 지워지므로,
        // 제출하는 사이에 사용자가 새 판을 시작했다면 새 판의 포인터는 건드리지 않는다.
        soloGameRepository.clearActiveSession(userId, gameSessionId);

        // 4. moves의 시각 검증 — 좌표 재생보다 먼저. 제한시간을 넘겨 찍힌 move가 있으면
        //    보드 로직을 돌려볼 필요 없이 거부한다.
        validateMoveTimes(request.moves());

        // 5~6. 시드로 최초 보드 재구성 → moves를 순서대로 재생하며 서버가 재검증
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

        // 7. 플레이 시간 — 서버 시계 기준, 제한시간을 상한으로
        long serverElapsedMs = System.currentTimeMillis() - session.getStartedAtMills();
        int playTimeSeconds = (int) Math.min(serverElapsedMs / 1000, TIME_LIMIT_SECONDS);

        // 제출이 제한시간보다 한참 늦게 도착했다면 클라이언트 타이머가 멈췄던 것이다.
        // 이제 TTL이 넉넉해 기록은 정상 저장되지만, 얼마나 늦는지는 관측해둔다.
        long lateMs = serverElapsedMs - TIME_LIMIT_SECONDS * 1000L;
        if (lateMs > LATE_SUBMIT_LOG_THRESHOLD_MS) {
            log.info("지연 제출 — userId={} 제한시간보다 {}초 늦게 도착 (브라우저 타이머 정지 추정, 세션 TTL {}초)",
                    userId, lateMs / 1000, SESSION_TTL_SECONDS);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.NOT_FOUND));

        // 8. isPersonalBest는 INSERT "전에" 판정 — 저장 후 조회하면 방금 넣은 기록과 비교하게 된다
        int previousBest = soloRecordRepository.findTopByUserIdOrderByScoreDesc(userId)
                .map(SoloRecord::getScore)
                .orElse(-1); // 첫 게임이면 무조건 갱신
        boolean isPersonalBest = score > previousBest;

        SoloRecord record = soloRecordRepository.save(SoloRecord.create(
                user, score, clearedCount, playTimeSeconds, String.valueOf(session.getBoardSeed())));

        // 랭킹 캐시 갱신 — 두 키(alltime/weekly)에 ZADD GT, GT이기에 기존 최고점보다 낮은 점수는 알아서 무시
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            rankingRedisRepository.updateScore(RankingPeriod.ALLTIME.redisKey(today), userId, score, false);
            rankingRedisRepository.updateScore(RankingPeriod.WEEKLY.redisKey(today), userId, score, true);
        } catch (Exception e) {
            // Redis 장애가 기록 저장(DB 커밋)을 실패시키면 안되기에 log로만 남긴다
            // 여기서 생긴 DB-캐시 불일치는 키가 사라진 뒤 warm-up이 복구한다
            log.warn("랭킹 캐시 갱신 실패 — warm-up 시 복구됨", e);
        }

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

    /**
     * moves의 시각 검증 — 두 가지를 본다.
     *  1) 제한시간 준수: 모든 move의 elapsedMs가 [0, TIME_LIMIT + 오차] 안에 있어야 한다.
     *     제한시간이 끝난 뒤 찍힌 move는 게임 규칙상 존재할 수 없다.
     *  2) 단조 증가: moves는 보낸 순서대로 재생하므로 시각도 그 순서를 따라야 한다.
     *     순서가 뒤집혔다면 클라이언트가 기록을 조립한 것이다.
     *
     * 한계를 분명히 해둔다 — elapsedMs는 클라이언트가 자기 시계로 적은 값이다.
     * 이 검증은 '정직한 클라이언트가 제한시간을 넘겨 제출하는 것'을 막지만,
     * 값을 작게 위조하는 클라이언트는 막지 못한다. 점수 자체는 좌표 재생으로 서버가
     * 다시 계산하므로(board.clear) 위조로 얻을 수 있는 이득은 '생각할 시간'뿐이다.
     */
    private void validateMoveTimes(List<SoloReqDTO.Move> moves) {
        long limitMs = TIME_LIMIT_SECONDS * 1000L + MOVE_TIME_GRACE_MS;
        long previousMs = -1;

        for (SoloReqDTO.Move move : moves) {
            if (move.elapsedMs() < 0 || move.elapsedMs() > limitMs) {
                throw new CustomException(SoloErrorCode.MOVES_OUT_OF_TIME);
            }
            if (move.elapsedMs() < previousMs) {
                throw new CustomException(SoloErrorCode.MOVES_OUT_OF_TIME);
            }
            previousMs = move.elapsedMs();
        }
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) return DEFAULT_SIZE;
        return Math.min(size, MAX_SIZE);
    }
}
