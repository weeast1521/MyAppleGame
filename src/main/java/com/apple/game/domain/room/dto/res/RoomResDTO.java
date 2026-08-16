package com.apple.game.domain.room.dto.res;

public class RoomResDTO {

    public record Create(
            String roomCode,
            String status
    ){
    }

    public record Join(
            String roomCode,
            String status,
            PlayerInfo host,
            PlayerInfo guest
    ){
    }

    public record PlayerInfo(
            Long userId,
            String nickname
    ){
    }
}
