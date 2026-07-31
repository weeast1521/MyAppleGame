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
@Table(name = "solo_record")
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
