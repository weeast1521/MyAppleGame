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
    // 좌표 재생이 '무엇을 지웠는가'를 검증하듯, 이쪽은 '언제 지웠는가'를 검증한다.
    // INVALID_MOVES와 코드를 나눈 이유: 실서버 로그에서 원인이 바로 갈린다 —
    // SOLO400은 보드 로직/시드 문제, SOLO400_1은 제한시간 문제.
    MOVES_OUT_OF_TIME(HttpStatus.BAD_REQUEST, "SOLO400_1", "제한시간을 벗어난 기록입니다."),

    // 404
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "SOLO404", "존재하지 않거나 만료된 게임 세션입니다."),

    // 409
    ALREADY_SUBMITTED(HttpStatus.CONFLICT, "SOLO409", "이미 제출된 게임 세션입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}