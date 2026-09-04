package com.apple.game.domain.solo.entity;

import com.apple.game.domain.user.entity.User;
import com.apple.game.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 인덱스 3개 — 각각 다른 쿼리를 위한 것 (근거: docs/db_performance.md E2, 200만 건 실측)
//  user_id            : 내 기록 커서 조회(WHERE user_id = ? AND id < ? ORDER BY id DESC) — InnoDB 보조 인덱스는 PK(id)가 붙어 정렬까지 커버
//  user_id, score     : 전체 랭킹 GROUP BY user_id MAX(score) → 커버링 skip scan (1,325ms → 14ms)
//  created_at, user_id, score : 주간 랭킹 created_at 범위 + GROUP BY → 커버링 range scan (1,415ms → 43ms)
// 비용: INSERT마다 보조 인덱스 3개 갱신. 기록은 판당 1회 INSERT라 감당 가능(E5: 1만 건 batch 110ms).
@Table(name = "solo_record", indexes = {
        @Index(name = "idx_solo_record_user_id", columnList = "user_id"),
        @Index(name = "idx_solo_record_user_score", columnList = "user_id, score"),
        @Index(name = "idx_solo_record_created_user_score", columnList = "created_at, user_id, score")
})
public class SoloRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private int score;

    @Column(name = "cleared_count", nullable = false)
    private int clearedCount;       // 10 조합 성공 횟수

    @Column(name = "play_time_seconds", nullable = false)
    private int playTimeSeconds;    // 실제 플레이 시간

    @Column(name = "board_seed", nullable = false)
    private String boardSeed;       // 보드 재현용 시드

    private SoloRecord(User user, int score, int clearedCount, int playTimeSeconds, String boardSeed) {
        this.user = user;
        this.score = score;
        this.clearedCount = clearedCount;
        this.playTimeSeconds = playTimeSeconds;
        this.boardSeed = boardSeed;
    }

    public static SoloRecord create(User user, int score, int clearedCount, int playTimeSeconds, String boardSeed) {
        return new SoloRecord(user, score, clearedCount, playTimeSeconds, boardSeed);
    }
}
