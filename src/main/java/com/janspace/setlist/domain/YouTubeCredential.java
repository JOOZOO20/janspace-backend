package com.janspace.setlist.domain;

import com.janspace.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 잔스페이스 유튜브 채널의 OAuth 리프레시 토큰. 단일 행(id = 1). 관리자 OAuth 콜백이 갱신한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "youtube_credentials")
public class YouTubeCredential extends BaseEntity {

    public static final byte SINGLETON_ID = 1;

    @Id
    private Byte id;

    @Column(name = "refresh_token", nullable = false, length = 512)
    private String refreshToken;

    @Column(name = "channel_id", length = 64)
    private String channelId;

    public YouTubeCredential(String refreshToken, String channelId) {
        this.id = SINGLETON_ID;
        this.refreshToken = refreshToken;
        this.channelId = channelId;
    }

    public void replace(String refreshToken, String channelId) {
        this.refreshToken = refreshToken;
        this.channelId = channelId;
    }
}
