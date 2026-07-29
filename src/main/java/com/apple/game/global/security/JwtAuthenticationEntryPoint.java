package com.apple.game.global.security;

import com.apple.game.domain.auth.exception.AuthErrorCode;
import com.apple.game.global.apiPayload.CustomResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        AuthErrorCode errorCode = AuthErrorCode.UNAUTHORIZED;

        log.warn("[{}] {} {} | 인증되지 않은 요청: {}",
                errorCode.getCode(), request.getMethod(), request.getRequestURI(), authException.getMessage());

        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());   // getWriter() 호출 전에 설정해야 한다

        objectMapper.writeValue(response.getWriter(), CustomResponse.onFail(errorCode));
    }
}