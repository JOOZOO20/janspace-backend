package com.janspace.account.domain;

import com.janspace.common.entity.SoftDeletableBaseEntity;
import com.janspace.common.locale.SupportedLocale;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자. 첫 소셜 로그인이 곧 가입이며 탈퇴는 소프트 삭제다.
 */
@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends SoftDeletableBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    // 유니크 아님: 같은 이메일의 다른 제공자 계정은 다른 사용자다
    @Column(name = "email", length = 255)
    private String email;

    @Convert(converter = SupportedLocaleConverter.class)
    @Column(name = "locale", nullable = false, length = 10)
    private SupportedLocale locale;

    @Column(name = "is_admin", nullable = false)
    private boolean admin;

    @Embedded
    private Departure departure;

    @Column(name = "consented_at", columnDefinition = "DATETIME(6)")
    private Instant consentedAt;

    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    public User(String nickname, String email) {
        this.nickname = nickname;
        this.email = email;
        this.locale = SupportedLocale.KO;
    }

    public boolean isConsented() {
        return consentedAt != null;
    }

    public void consent(String termsVersion, Instant at) {
        this.termsVersion = termsVersion;
        this.consentedAt = at;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changeLocale(SupportedLocale locale) {
        this.locale = locale;
    }

    public void changeDeparture(Departure departure) {
        this.departure = departure;
    }

    public void withdraw(Instant at) {
        super.delete(at);
    }
}
