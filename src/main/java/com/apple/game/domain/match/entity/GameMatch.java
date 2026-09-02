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

    // 낙관적 락 — 타이머(TIME_UP)와 이탈 처리(leave)가 동시에 정산을 시도할 수 있다.
    // 둘 다 PLAYING 상태를 읽고 각자 종료를 쓰면 정산이 두 번 실행되므로,
    // UPDATE 시 version이 읽은 값과 다르면 실패시켜(OptimisticLockingFailure) 한쪽만 이긴다.
    @Version
    private Long version;

    private GameMatch(String roomCode, String boardSeed) {
        this.roomCode = roomCode;
        this.boardSeed = boardSeed;
        this.status = MatchStatus.PLAYING;
        this.startedAt = LocalDateTime.now();
    }

    public static GameMatch start(String roomCode, String boardSeed) {
        return new GameMatch(roomCode, boardSeed);
    }

    public boolean isPlaying() {
        return this.status == MatchStatus.PLAYING;
    }

    // 정상 종료(TIME_UP) — 정산과 함께 호출된다
    public void finish() {
        this.status = MatchStatus.FINISHED;
        this.finishedAt = LocalDateTime.now();
    }

    // 비정상 종료(게임 중 이탈 등) — 판 무효, 정산 없음
    public void abort() {
        this.status = MatchStatus.ABORTED;
        this.finishedAt = LocalDateTime.now();
    }
}
