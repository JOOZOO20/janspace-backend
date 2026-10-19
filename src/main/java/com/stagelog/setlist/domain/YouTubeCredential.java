package com.stagelog.setlist.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stagelog 유튜브 채널의 OAuth 리프레시 토큰. 단일 행(id = 1). 관리자 OAuth 콜백이 갱신한다.
 * invalidAt 이 있으면 토큰이 폐기·만료된 것이라 자동 동기화를 멈추고 다시 연결을 기다린다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "youtube_credentials")
public class YouTubeCredential extends BaseEntity {

    public static final byte SINGLETON_ID = 1;

    @Id
    private Byte id;

    // 저장값은 암호문(RefreshTokenCipher). 구글 리프레시 토큰 최대 512바이트 + IV·태그·base64 를 담도록 1024
    @Convert(converter = RefreshTokenCipher.class)
    @Column(name = "refresh_token", nullable = false, length = 1024)
    private String refreshToken;

    @Column(name = "invalid_at", columnDefinition = "DATETIME(6)")
    private Instant invalidAt;

    public YouTubeCredential(String refreshToken) {
        this.id = SINGLETON_ID;
        this.refreshToken = refreshToken;
    }

    public void replace(String refreshToken) {
        this.refreshToken = refreshToken;
        this.invalidAt = null;
    }
}
