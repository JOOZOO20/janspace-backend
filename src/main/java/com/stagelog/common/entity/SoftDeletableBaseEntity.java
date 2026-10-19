package com.stagelog.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;

/**
 * 소프트 삭제 엔티티 부모. delete 는 protected 이므로 도메인 엔티티가 의도가 드러나는 public 메서드로 감싼다.
 */
@Getter
@MappedSuperclass
public abstract class SoftDeletableBaseEntity extends BaseEntity {

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private Instant deletedAt;

    protected void delete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
