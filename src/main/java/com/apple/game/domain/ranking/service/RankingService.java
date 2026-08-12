package com.apple.game.domain.ranking.service;

import com.apple.game.domain.ranking.dto.res.RankingResDTO;
import com.apple.game.domain.ranking.entity.RankingPeriod;
import com.apple.game.domain.solo.entity.SoloRecord;
import com.apple.game.domain.solo.repository.SoloRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RankingService {

    private static final int MAX_RANGE = 100;

    private final SoloRecordRepository soloRecordRepository;

    @Transactional(readOnly = true)
    public RankingResDTO.RankingPage getRanking(Long userId, String periodParam, int offset, int size) {
        RankingPeriod period = RankingPeriod.from(periodParam);
        // 현재 로컬에서는 괜찮지만 EC2의 경우 UTC가 기본이기에 따로 설정이 필요
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        offset = Math.max(0, Math.min(offset, MAX_RANGE));
        size = Math.max(1, Math.min(size, MAX_RANGE - offset));

        return loadFromDb(period, today, userId, offset, size);
    }

    private RankingResDTO.RankingPage loadFromDb(
            RankingPeriod period, LocalDate today, Long userId, int offset, int size) {
        LocalDateTime from = period.aggregateFrom(today);

        List<SoloRecordRepository.RankingRow> rows = (from == null)
                ? soloRecordRepository.findAllTimeRanking(PageRequest.of(0, offset + size))
                : soloRecordRepository.findWeeklyRanking(from, PageRequest.of(0, offset + size));

        List<RankingResDTO.RankingItem> rankings = new ArrayList<>();
        for (int i = offset; i < Math.min(offset + size, rows.size()); i++) {
            var row = rows.get(i);
            rankings.add(new RankingResDTO.RankingItem(i + 1, row.getNickname(), row.getBestScore()));
        }

        RankingResDTO.MyRank myRank = null;
        if (userId != null) {
            Integer myBest = (from == null)
                    ? soloRecordRepository.findTopByUserIdOrderByScoreDesc(userId)
                    .map(SoloRecord::getScore).orElse(null)
                    : soloRecordRepository.findMyWeeklyBestScore(userId, from);
            if (myBest != null) {
                long above = (from == null)
                        ? soloRecordRepository.countUsersWithScoreAbove(myBest)
                        : soloRecordRepository.findMyWeeklyRanking(from, myBest);
                myRank = new RankingResDTO.MyRank((int) above + 1, myBest);
            }
        }
        return new RankingResDTO.RankingPage("db", rankings, myRank);
    }
}
