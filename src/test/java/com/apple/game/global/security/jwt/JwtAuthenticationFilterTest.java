package com.apple.game.global.security.jwt;

import com.apple.game.domain.user.entity.Role;
import com.apple.game.global.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtAuthenticationFilter 계약:
 *  - 유효한 access 토큰이면 SecurityContext에 (userId, role) principal을 채운다 — DB를 읽지 않는다
 *  - refresh·typ 없는 옛 토큰·토큰 없음이면 아무것도 채우지 않는다(익명 통과 → 보호된 API는 401)
 *  - 어느 경우든 다음 필터로 넘긴다
 */
class JwtAuthenticationFilterTest {

    final JwtTokenProvider provider = JwtTokenProviderTest.provider();
    final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(provider);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Bearer 헤더로 필터를 한 번 통과시키고, 그 뒤 SecurityContext의 인증 정보를 돌려준다 */
    Authentication runWithBearer(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("인증 여부와 무관하게 다음 필터로 넘어가야 한다").isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("access 토큰이면 토큰 클레임만으로 (userId, role)을 채운다")
    void accessTokenAuthenticates() throws Exception {
        Authentication auth = runWithBearer(provider.createAccessToken(7L, Role.ADMIN));

        assertThat(auth).isNotNull();
        CustomUserDetails principal = (CustomUserDetails) auth.getPrincipal();
        assertThat(principal.getUserId()).isEqualTo(7L);
        assertThat(principal.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("refresh 토큰을 Bearer로 보내면 인증되지 않는다 — 예전엔 role DB 폴백을 타고 통과했다")
    void refreshTokenDoesNotAuthenticate() throws Exception {
        assertThat(runWithBearer(provider.createRefreshToken(7L))).isNull();
    }

    @Test
    @DisplayName("typ 없는 옛 토큰은 role이 있어도 인증되지 않는다")
    void legacyTokenDoesNotAuthenticate() throws Exception {
        assertThat(runWithBearer(JwtTokenProviderTest.legacyToken(7L, Role.USER))).isNull();
        assertThat(runWithBearer(JwtTokenProviderTest.legacyToken(7L, null))).isNull();
    }

    @Test
    @DisplayName("토큰이 없으면 익명으로 통과한다")
    void noTokenPassesAnonymously() throws Exception {
        assertThat(runWithBearer(null)).isNull();
    }
}
