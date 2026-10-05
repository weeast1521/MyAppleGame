package com.apple.game.global.security.jwt;

import com.apple.game.domain.user.entity.Role;
import com.apple.game.global.security.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청이 들어올 때마다 Authorization 헤더에서 토큰을 꺼내(resolveToken) 서명·만료를 검증하고,
 * 유효하면 SecurityContext에 Authentication을 채워 넣는다.
 * 핵심은 주석에 적어두신 대로 토큰이 없거나 틀려도 여기서 막지 않고 익명 상태로 그냥 통과시킨다는 점이다.
 * "이 사람은 인증됐다/안 됐다"만 표시하고, "그래서 접근을 허용/거부한다"는 판단은 뒤쪽 인가 단계와 EntryPoint에 넘깁
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        // 유효한 access 토큰일 때만 인증 정보를 채운다.
        // 없거나 잘못됐으면 그냥 익명으로 통과시킨다 — 401 판정은 인가 단계와 EntryPoint 의 몫.
        // refresh 토큰은 서명이 유효해도 여기서 걸러진다(typ 검사) — 14일짜리 토큰이 API 인증에 쓰이면 안 된다.
        if (token != null && jwtTokenProvider.validateAccessToken(token)) {
            authenticate(token);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        Long userId = jwtTokenProvider.getUserId(token);
        Role role = jwtTokenProvider.getRole(token);

        // B안(Step 14): role은 access 토큰 클레임에서 읽고 DB를 읽지 않는다. access는 발급 시 항상 role을 싣는다.
        // 측정 근거(docs/db_performance.md E4): A안은 모든 인증 요청에 users SELECT 1번이 붙어
        // 200 VU 부하에서 처리량 1406 → 840 req/s, p95 142 → 231 ms. 커넥션 풀(10)이 그 SELECT로 포화.
        // 트레이드오프: 탈퇴·권한 변경이 액세스 토큰 만료(30분)까지 반영되지 않는다 — 재발급이 DB를 읽어 따라잡는다.
        // (예전의 "role이 없으면 DB 조회" 전환기 분기는 제거했다 — role 없는 토큰이 refresh뿐이라 refresh가 그 길로 인증을 통과했다)

        CustomUserDetails principal = new CustomUserDetails(userId, role);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
