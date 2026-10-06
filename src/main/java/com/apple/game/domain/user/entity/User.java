package com.apple.game.domain.user.entity;

import com.apple.game.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "users",
        uniqueConstraints = { // 데이터 무결성 강제
                @UniqueConstraint(name = "uk_users_login_id", columnNames = "login_id"),
                @UniqueConstraint(name = "uk_users_nickname", columnNames = "nickname"),
                @UniqueConstraint(name = "uk_users_provider", columnNames = {"provider", "provider_id"})
        }
)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Provider provider;

    @Column(name = "provider_id")
    private String providerId;

    // 로그인 식별자. 이메일이었다가 #59 에서 임의의 아이디로 전환(V4) — 이메일은 더 이상 수집하지 않는다.
    // 전환 전 가입자는 기존 이메일 문자열이 그대로 아이디다. 소셜 계정(미사용)은 NULL 가능.
    @Column(name = "login_id", nullable = true)
    private String loginId;

    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 15)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Role role;

    @Version
    private Long version;

    private User(Provider provider, String providerId, String loginId, String password, String nickname) {
        this.provider = provider;
        this.providerId = providerId;
        this.loginId = loginId;
        this.password = password;
        this.nickname = nickname;
        this.role = Role.USER;
    }

    public static User createLocalUser(String loginId, String encodedPassword, String nickname) {
        return new User(Provider.LOCAL, null, loginId, encodedPassword, nickname);
    }

    // PATCH /api/users/me/nickname
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }
}
