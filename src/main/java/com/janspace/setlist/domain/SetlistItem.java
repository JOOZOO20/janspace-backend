package com.janspace.setlist.domain;

import com.janspace.common.entity.BaseEntity;
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
 * 셋리 현재 항목. 행사·곡은 다른 기능의 엔티티라 ID 로만 참조한다 (FK 는 스키마에).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "setlist_items")
public class SetlistItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "song_id", nullable = false)
    private Long songId;

    @Column(nullable = false)
    private int position;

    @Column(name = "in_setlistfm", nullable = false)
    private boolean inSetlistFm;

    public SetlistItem(Long eventId, Long songId, int position, boolean inSetlistFm) {
        this.eventId = eventId;
        this.songId = songId;
        this.position = position;
        this.inSetlistFm = inSetlistFm;
    }
}
