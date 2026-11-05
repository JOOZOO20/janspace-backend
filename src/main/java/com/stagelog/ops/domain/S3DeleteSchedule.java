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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이전 S3 key 삭제 예약 한 건 (key 당 1행). 쓰기는 저장소의 native upsert·완료 표시로만 하고 시각은 DB 시계(NOW(6))로 정한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "s3_delete_schedule")
public class S3DeleteSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "object_key", nullable = false, unique = true, length = 255)
    private String objectKey;

    @Column(name = "delete_after", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant deleteAfter;

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private Instant deletedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private S3DeleteReason reason;
}
