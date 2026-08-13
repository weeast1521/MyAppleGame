package com.apple.game.domain.ranking.entity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.WeekFields;

public enum RankingPeriod {
    ALLTIME, WEEKLY;

    public static RankingPeriod from(String value) {
        return "weekly".equalsIgnoreCase(value) ? WEEKLY : ALLTIME;
    }

    /** ranking:solo:alltime  /  ranking:solo:weekly:202633 */
    public String redisKey(LocalDate today) {
        if (this == ALLTIME) return "ranking:solo:alltime";

        int year = today.get(WeekFields.ISO.weekBasedYear());
        int week = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        return String.format("ranking:solo:weekly:%d%02d", year, week);
    }

    // DB에서 언제부터의 기록을 긁어올지, 그 시작 시각을 알려주는 메서드
    public LocalDateTime aggregateFrom(LocalDate today) {
        if (this == ALLTIME) return null;
        // 날짜(LocalDate)를 시각까지 붙은 LocalDateTime으로 바뀜. 2026-08-10 → 2026-08-10T00:00
        // with() => 특정 필드만 바꾼 새 복사본을 만들어주는 메서드 / LocalDate 자체가 immutable 이기에 복사본 필요
        return today.with(DayOfWeek.MONDAY).atStartOfDay();
    }
}
