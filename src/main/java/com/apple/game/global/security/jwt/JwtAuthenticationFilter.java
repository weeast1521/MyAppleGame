package com.apple.game.global.security.jwt;

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

        // A안: role 이 토큰에 없으므로 매 요청 DB 에서 사용자를 읽는다 (비용은 Step 14 에서 측정)
        User user = userRepository.findById(userId).orElse(null);

        if (user == null) {
            // 서명은 유효하지만 탈퇴 등으로 사라진 사용자 → 인증하지 않고 넘긴다
            log.debug("존재하지 않는 사용자의 토큰입니다. userId={}", userId);
            return;
        }

        CustomUserDetails principal = new CustomUserDetails(user);

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
