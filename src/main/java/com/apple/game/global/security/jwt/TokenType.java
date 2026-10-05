package com.apple.game.global.security.jwt;

// 토큰 종류 — typ 클레임 값. 두 토큰은 같은 키로 서명되므로 서명만으로는 구분되지 않는다.
// "role이 없으면 refresh" 같은 추측 대신 종류를 토큰에 명시하고, 쓰는 곳마다 기대하는 종류를 검사한다.
public enum TokenType {
    ACCESS,  // API·STOMP CONNECT 인증용 (30분)
    REFRESH  // /api/auth/reissue 전용 (14일)
}
