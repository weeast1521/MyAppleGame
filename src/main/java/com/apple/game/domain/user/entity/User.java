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
                @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
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

    @Column(nullable = true)
    private String email;

    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 15)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Role role;

    @Version
    private Long version;

    private User(Provider provider, String providerId, String email, String password, String nickname) {
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.role = Role.USER;
    }

    public static User createLocalUser(String email, String encodedPassword, String nickname) {
        return new User(Provider.LOCAL, null, email, encodedPassword, nickname);
    }

    // PATCH /api/users/me/nickname
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }
}
