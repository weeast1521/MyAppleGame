package com.apple.game.global.exception;

import com.apple.game.global.apiPayload.CustomResponse;
import com.apple.game.global.apiPayload.code.BaseErrorCode;
import com.apple.game.global.apiPayload.code.GeneralErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.file.AccessDeniedException;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 우리가 의도적으로 던진 비즈니스 예외
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<CustomResponse<Void>> handleCustom(CustomException e, HttpServletRequest request) {
        return toResponse(e.getCode(), e, request);
    }

    // @Valid 검증 실패 — 프론트가 제일 자주 마주침
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CustomResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        e.getBindingResult().getFieldErrors().forEach(err ->
                errors.put(err.getField(), err.getDefaultMessage()));

        return toResponse(GeneralErrorCode.VALIDATION_FAILED, errors, e, request);
    }

    // JSON 형식 오류
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomResponse<Void>> handleUnreadable(
            HttpMessageNotReadableException e, HttpServletRequest request) {
        return toResponse(GeneralErrorCode.UNREADABLE_MESSAGE, e, request);
    }

    // 잘못된 경로 — Boot 3.2+ 에서 이거 없으면 Whitelabel 페이지가 나감
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CustomResponse<Void>> handleNoResource(
            NoResourceFoundException e, HttpServletRequest request) {
        return toResponse(GeneralErrorCode.NOT_FOUND, e, request);
    }

    @ExceptionHandler(AccessDeniedException.class)   // org.springframework.security.access
    public ResponseEntity<CustomResponse<Void>> handleAccessDenied(
            AccessDeniedException e, HttpServletRequest request) {
        return toResponse(GeneralErrorCode.FORBIDDEN, e, request);
    }

    // 최종 방어선
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomResponse<Void>> handleException(
            Exception e, HttpServletRequest request) {
        return toResponse(GeneralErrorCode.INTERNAL_SERVER_ERROR, e, request);
    }

    // ----- 공통 헬퍼 -----
    private ResponseEntity<CustomResponse<Void>> toResponse(
            BaseErrorCode code, Exception e, HttpServletRequest request) {
        return this.<Void>toResponse(code, null, e, request);
    }

    private <T> ResponseEntity<CustomResponse<T>> toResponse(
            BaseErrorCode code, T result, Exception e, HttpServletRequest request) {
        logByStatus(code, e, request);

        return ResponseEntity.status(code.getStatus())
                .body(CustomResponse.onFail(code, result));
    }

    /**
     * 5xx = 예상하지 못한 버그 → error + 스택트레이스
     * 4xx = 클라이언트 요청 문제 → warn + 한 줄 요약
     */
    private void logByStatus(BaseErrorCode code, Exception e, HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        if (code.getStatus().is5xxServerError()) {
            log.error("[{}] {} {} | {}", code.getCode(), method, uri, e.getMessage(), e);
        } else {
            log.warn("[{}] {} {} | {}: {}", code.getCode(), method, uri, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
