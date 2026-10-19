package com.stagelog.setlist.domain;

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
 * 셋리 리비전(역사). snapshot 은 그 시점의 전체 항목 JSON(SnapshotCodec). editorUserId 는 내부 전용이며 응답에 나가지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "setlist_revisions")
public class SetlistRevision extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private int version;

    @Column(name = "editor_user_id")
    private Long editorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RevisionSource source;

    @Column(nullable = false, columnDefinition = "json")
    private String snapshot;

    public SetlistRevision(Long eventId, int version, Long editorUserId, RevisionSource source, String snapshot) {
        this.eventId = eventId;
        this.version = version;
        this.editorUserId = editorUserId;
        this.source = source;
        this.snapshot = snapshot;
    }
}
