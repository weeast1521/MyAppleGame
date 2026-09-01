package com.apple.game.domain.room.exception;

import com.apple.game.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum RoomErrorCode implements BaseErrorCode {
    // 400
    ROOM_SELF_JOIN(HttpStatus.BAD_REQUEST, "ROOM400", "자기 방에는 입장할 수 없습니다."),

    // 404
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "ROOM404", "존재하지 않는 방입니다."),

    // 409
    ROOM_FULL(HttpStatus.CONFLICT, "ROOM409", "방이 가득 찼습니다."),
    ROOM_PLAYING(HttpStatus.CONFLICT, "ROOM409_1", "이미 게임이 진행 중인 방입니다."),

    // 500
    ROOM_CODE_EXHAUSTED(HttpStatus.INTERNAL_SERVER_ERROR, "ROOM500", "방 코드 생성에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
