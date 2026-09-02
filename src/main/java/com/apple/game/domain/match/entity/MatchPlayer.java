package com.apple.game.domain.match.entity;

import com.apple.game.domain.user.entity.User;
import com.apple.game.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 판(game_match)에 대한 플레이어별 결과 — 판마다 2행이 만들어진다.
 * Redis의 휘발 점수를 판 종료 시점에 영속화하는 write-behind의 대상 테이블.
 * (match_id, user_id) UNIQUE — 정산이 어떤 경로로든 두 번 실행돼도 DB가 최후 방어선이 된다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "match_player",
        uniqueConstraints = @UniqueConstraint(name = "uk_match_player_match_user", columnNames = {"match_id", "user_id"}),
        indexes = @Index(name = "idx_match_player_user", columnList = "user_id, id"))
public class MatchPlayer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private GameMatch match;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchResult result;

    private MatchPlayer(GameMatch match, User user, int score, MatchResult result) {
        this.match = match;
        this.user = user;
        this.score = score;
        this.result = result;
    }

    public static MatchPlayer of(GameMatch match, User user, int score, MatchResult result) {
        return new MatchPlayer(match, user, score, result);
    }
}
