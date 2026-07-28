package com.apple.game.domain.auth.exception;

import com.apple.game.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {

    // 409
    EMAIL_CONFLICT(HttpStatus.CONFLICT, "AUTH409", "이미 가입한 email입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}