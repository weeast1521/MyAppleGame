package com.apple.game.global.exception;

import com.apple.game.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;

@Getter
public class CustomException extends RuntimeException {

    private final BaseErrorCode code;

    public CustomException(BaseErrorCode code) {
        super(code.getMessage());
        this.code = code;
    }
}
