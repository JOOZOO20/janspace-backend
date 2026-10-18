package com.janspace.ops.domain;

import com.janspace.common.entity.BaseEntity;
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
 * 동기화 실행 한 건. 시작 시 RUNNING 으로 만들어지고 finish 로 한 번 닫힌다. 운영 이력이므로 물리 삭제만 의미가 있어 BaseEntity 를 쓴다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "sync_runs")
public class SyncRun extends BaseEntity {

    static final int SUMMARY_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncSource source;

    @Column(name = "started_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant startedAt;

    @Column(name = "finished_at", columnDefinition = "DATETIME(6)")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncStatus status;

    @Column(length = SUMMARY_MAX_LENGTH)
    private String summary;

    private SyncRun(SyncSource source, Instant startedAt) {
        this.source = source;
        this.startedAt = startedAt;
        this.status = SyncStatus.RUNNING;
    }

    public static SyncRun start(SyncSource source, Instant startedAt) {
        return new SyncRun(source, startedAt);
    }

    // 종료 기록. 요약이 컬럼 길이를 넘으면 잘라서라도 남긴다 (종료 기록 실패로 RUNNING 이 영원히 남는 것을 막는다)
    public void finish(SyncStatus status, String summary, Instant finishedAt) {
        if (status == SyncStatus.RUNNING) {
            throw new IllegalArgumentException("종료 상태는 OK 또는 FAILED 여야 합니다");
        }
        this.status = status;
        this.finishedAt = finishedAt;
        this.summary = summary != null && summary.length() > SUMMARY_MAX_LENGTH
                ? summary.substring(0, SUMMARY_MAX_LENGTH) : summary;
    }
}
