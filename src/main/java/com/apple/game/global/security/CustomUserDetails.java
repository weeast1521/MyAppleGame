package com.apple.game.global.security;

import com.apple.game.domain.user.entity.Role;
import com.apple.game.domain.user.entity.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

// security가 정한 사용자 정보 인터페이스(규격). 인증에 필요한 최소 정보(userId, role)만 든다 —
// User 엔티티를 통째로 들고 있으면 인증마다 DB 조회가 강제된다(Step 14에서 role을 토큰 클레임으로 옮기며 분리).
@Getter
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {
    private final Long userId;
    private final Role role;

    public static CustomUserDetails of(User user) {
        return new CustomUserDetails(user.getId(), user.getRole());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // hasRole("ADMIN") 은 내부적으로 "ROLE_ADMIN" 을 찾으므로 접두사를 직접 붙인다
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return null; // 비밀번호 검증은 로그인 시점(AuthService)에서만 — 인증된 principal은 비밀번호를 갖지 않는다
    }

    @Override
    public String getUsername() {
        return String.valueOf(userId);   // 식별자는 email 이 아니라 userId 로 통일
    }
}
