package com.stagelog.ops.domain;

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
 * 동기화 실행 안 항목 하나의 실패. 실패 항목이 있는 실행은 PARTIAL 로 끝난다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "sync_item_failures")
public class SyncItemFailure extends BaseEntity {

    static final int MESSAGE_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_run_id", nullable = false)
    private Long syncRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncSource provider;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(name = "error_code", nullable = false, length = 50)
    private String errorCode;

    @Column(nullable = false, length = MESSAGE_MAX_LENGTH)
    private String message;

    // 길이가 넘는 값은 잘라서라도 남긴다 (실패 기록이 실패해 원인이 사라지는 것을 막는다)
    public SyncItemFailure(Long syncRunId, SyncSource provider, String externalId, String errorCode, String message) {
        this.syncRunId = syncRunId;
        this.provider = provider;
        this.externalId = cut(externalId, 100);
        this.errorCode = cut(errorCode, 50);
        this.message = cut(message == null ? "" : message, MESSAGE_MAX_LENGTH);
    }

    private static String cut(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
