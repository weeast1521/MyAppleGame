package com.apple.game.global.security.jwt;

import com.apple.game.domain.user.entity.Role;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.security.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        // 토큰이 유효할 때만 인증 정보를 채운다.
        // 없거나 잘못됐으면 그냥 익명으로 통과시킨다 — 401 판정은 인가 단계와 EntryPoint 의 몫.
        if (token != null && jwtTokenProvider.validate(token)) {
            authenticate(token);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        Long userId = jwtTokenProvider.getUserId(token);
        Role role = jwtTokenProvider.getRole(token);

        // B안(Step 14): role이 토큰 클레임에 있으면 DB를 읽지 않는다.
        // 측정 근거(docs/db_performance.md E4): A안은 모든 인증 요청에 users SELECT 1번이 붙어
        // 200 VU 부하에서 처리량 1406 → 840 req/s, p95 142 → 231 ms. 커넥션 풀(10)이 그 SELECT로 포화.
        // 트레이드오프: 탈퇴·권한 변경이 액세스 토큰 만료(30분)까지 반영되지 않는다 — 재발급이 DB를 읽어 따라잡는다.
        if (role == null) {
            // 전환기: 이 배포 전에 발급된 토큰(role 클레임 없음)은 예전처럼 DB 조회로 처리
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.debug("존재하지 않는 사용자의 토큰입니다. userId={}", userId);
                return;
            }
            role = user.getRole();
        }

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
