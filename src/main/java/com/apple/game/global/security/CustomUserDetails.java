package com.apple.game.global.security;

import com.apple.game.domain.user.entity.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

// security가 정한 사용자 정보 인터페이스(규격) -> User Entity를 Security가 이해 가능한 형태로 감싸주는 어댑터 역할
@Getter
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {
    private final User user;

    // 컨트롤러/서비스에서 @AuthenticationPrincipal 로 받아 바로 꺼내 쓴다
    public Long getUserId() {
        return user.getId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // hasRole("ADMIN") 은 내부적으로 "ROLE_ADMIN" 을 찾으므로 접두사를 직접 붙인다
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return String.valueOf(user.getId());   // 식별자는 email 이 아니라 userId 로 통일
    }
}
