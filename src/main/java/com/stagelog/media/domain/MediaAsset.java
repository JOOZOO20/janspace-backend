package com.stagelog.media.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 변환 대상 원본 1개 (소유자 종류·ID·내용 sha256 당 1행). 같은 소유자가 원본을 바꾸면 새 행이 생기고, 서빙은 소유자의 현재 key 와 같은 행만 쓴다.
 * 생성 이력이므로 물리 삭제만 의미가 있어 BaseEntity 를 쓴다. owner_type 은 application 의 MediaOwnerType 이름이다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "media_assets")
public class MediaAsset extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_type", nullable = false, length = 20)
    private String ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "original_key", nullable = false)
    private String originalKey;

    @Column(name = "original_sha256", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String originalSha256;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "mime_type", nullable = false, length = 50)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssetStatus status;

    public MediaAsset(String ownerType, Long ownerId, String originalKey, String originalSha256, int width, int height, String mimeType) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.originalKey = originalKey;
        this.originalSha256 = originalSha256;
        this.width = width;
        this.height = height;
        this.mimeType = mimeType;
        this.status = AssetStatus.PENDING;
    }

    // 같은 내용이 다른 key 로 다시 저장됐다 (고정 key -> 버전 key 등). 서빙이 현재 key 로 찾을 수 있게 맞춘다
    public void moveOriginal(String key) {
        this.originalKey = key;
    }
}
