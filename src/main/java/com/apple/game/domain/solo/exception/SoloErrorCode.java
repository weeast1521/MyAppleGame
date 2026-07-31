package com.apple.game.domain.solo.exception;

import com.apple.game.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum SoloErrorCode implements BaseErrorCode {

    // 400
    INVALID_MOVES(HttpStatus.BAD_REQUEST, "SOLO400", "제출 기록 검증에 실패했습니다."),

    // 404
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "SOLO404", "존재하지 않거나 만료된 게임 세션입니다."),

    // 409
    ALREADY_SUBMITTED(HttpStatus.CONFLICT, "SOLO409", "이미 제출된 게임 세션입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}