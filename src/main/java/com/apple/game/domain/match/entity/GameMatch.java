package com.apple.game.domain.match.entity;

import com.apple.game.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "game_match", indexes = @Index(name = "idx_game_match_room_code", columnList = "room_code"))
public class GameMatch extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_code", nullable = false)
    private String roomCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchStatus status;

    @Column(name = "board_seed", nullable = false)
    private String boardSeed;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    private GameMatch(String roomCode, String boardSeed) {
        this.roomCode = roomCode;
        this.boardSeed = boardSeed;
        this.status = MatchStatus.PLAYING;
        this.startedAt = LocalDateTime.now();
    }

    public static GameMatch start(String roomCode, String boardSeed) {
        return new GameMatch(roomCode, boardSeed);
    }
}
