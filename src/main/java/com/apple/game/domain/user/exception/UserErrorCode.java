package com.apple.game.domain.user.exception;

import com.apple.game.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements BaseErrorCode {

    // 409
    NICKNAME_CONFLICT(HttpStatus.CONFLICT, "USER409", "이미 사용중인 닉네임입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
