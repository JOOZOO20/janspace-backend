package com.stagelog.setlist.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 병합 때 별칭·제목으로 곡에 연결되지 않은 setlist.fm 곡명. 관리자가 곡에 연결하면 삭제되고 병합을 다시 돌린다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "setlist_unmatched_names")
public class UnmatchedName extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "raw_name", nullable = false, length = 255)
    private String rawName;

    public UnmatchedName(Long eventId, String rawName) {
        this.eventId = eventId;
        this.rawName = rawName;
    }
}
