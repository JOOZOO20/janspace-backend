package com.janspace.setlist.domain;

import com.janspace.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사당 셋리 위키 페이지 하나 (PK = event_id). 현재 항목은 SetlistItem, 역사는 SetlistRevision.
 * order_confirmed(관리자 토글)와 merged_at(병합 잡 기록)은 독립 상태다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "setlist_pages")
public class SetlistPage extends BaseEntity {

    @Id
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "order_confirmed", nullable = false)
    private boolean orderConfirmed;

    @Column(name = "merged_at", columnDefinition = "DATETIME(6)")
    private Instant mergedAt;

    @Column(name = "youtube_playlist_id", length = 64)
    private String youtubePlaylistId;

    // 리비전마다 +1. 다음 리비전 번호 = version + 1
    @Column(nullable = false)
    private int version;

    public SetlistPage(Long eventId) {
        this.eventId = eventId;
    }

    public int nextVersion() {
        version++;
        return version;
    }

    public void confirmOrder(boolean confirmed) {
        this.orderConfirmed = confirmed;
    }

    public void markMerged(Instant at) {
        this.mergedAt = at;
    }

    public void changePlaylist(String playlistId) {
        this.youtubePlaylistId = playlistId;
    }
}
