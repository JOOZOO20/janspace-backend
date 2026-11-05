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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * asset 의 rendition 1개 (profile × format). 생성 뒤 상태 전이는 모두 MediaRenditionRepository 의 조건부 UPDATE(NOW(6))로만 한다.
 * profile·format 은 RenditionProfile.key()·RenditionFormat 소문자 이름으로 저장한다 (thumb, webp 등).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "media_renditions")
public class MediaRendition extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(nullable = false, length = 20)
    private String profile;

    @Column(nullable = false, length = 10)
    private String format;

    @Column(name = "output_key")
    private String outputKey;

    private Integer width;

    private Integer height;

    private Long bytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RenditionStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_expires_at", columnDefinition = "DATETIME(6)")
    private Instant leaseExpiresAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public MediaRendition(Long assetId, RenditionProfile profile, RenditionFormat format) {
        this.assetId = assetId;
        this.profile = profile.key();
        this.format = format.name().toLowerCase();
        this.status = RenditionStatus.PENDING;
    }

    public RenditionProfile profile() {
        return RenditionProfile.ofKey(profile);
    }

    public RenditionFormat format() {
        return RenditionFormat.valueOf(format.toUpperCase());
    }
}
