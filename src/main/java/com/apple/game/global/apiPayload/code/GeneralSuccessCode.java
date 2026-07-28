package com.apple.game.global.apiPayload.code;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum GeneralSuccessCode implements BaseSuccessCode {

    OK(HttpStatus.OK, "COMMON200", "요청에 성공했습니다."),
    CREATED(HttpStatus.CREATED, "COMMON201", "자원이 생성되었습니다."),
    DELETED(HttpStatus.NO_CONTENT, "COMMON204", "성공적으로 삭제되었습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
