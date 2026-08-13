package com.apple.game.domain.room.entity;

public enum RoomStatus {
    WAITING, // 게스트 혼자 대기
    READY, // 2명 입장 완료, 게임 시작 전
    PLAYING // 게임 진행 중
}
