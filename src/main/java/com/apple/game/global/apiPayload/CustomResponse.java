package com.apple.game.global.apiPayload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.apple.game.global.apiPayload.code.BaseErrorCode;
import com.apple.game.global.apiPayload.code.BaseSuccessCode;
import com.apple.game.global.apiPayload.code.GeneralSuccessCode;
import lombok.*;
import org.springframework.http.HttpStatus;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonPropertyOrder({"isSuccess", "code", "message", "result"})
@JsonInclude(JsonInclude.Include.NON_NULL) // T가 Void인 경우 "result": null이 나가지 않고 result 자체가 사라짐
public class CustomResponse<T> {

    @JsonProperty("isSuccess")
    private Boolean isSuccess;

    // body 에는 보내지 않고 ResponseEntity 상태 저장용으로 사용
    @JsonIgnore
    private HttpStatus status;

    @JsonProperty("code")
    private String code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("result")
    private T result;

    // ----- success ------
    public static CustomResponse<Void> onSuccess(BaseSuccessCode code) {
        return of(true, code.getStatus(), code.getCode(), code.getMessage(), null);
    }

    // static <T> -> "호출 시점에 결정하겠다는 약속"
    public static <T> CustomResponse<T> onSuccess(BaseSuccessCode code, T result) {
        return of(true, code.getStatus(), code.getCode(), code.getMessage(), result);
    }

    public static <T> CustomResponse<T> onSuccess(T result) {
        return onSuccess(GeneralSuccessCode.OK, result);
    }

    // ----- fail -----
    public static CustomResponse<Void> onFail(BaseErrorCode code) {
        return of(false, code.getStatus(), code.getCode(), code.getMessage(), null);
    }

    public static <T> CustomResponse<T> onFail(BaseErrorCode code, T result) {
        return of(false, code.getStatus(), code.getCode(), code.getMessage(), result);
    }
    // ----- internal -----
    private static <T> CustomResponse<T> of(boolean isSuccess, HttpStatus status, String code, String message, T result) {
        return CustomResponse.<T>builder()
                .isSuccess(isSuccess)
                .status(status)
                .code(code)
                .message(message)
                .result(result)
                .build();
    }
}
