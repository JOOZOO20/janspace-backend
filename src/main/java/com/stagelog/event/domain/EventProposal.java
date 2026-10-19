package com.stagelog.event.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 팬 제안. user_id 는 users 를 가리키지만 FK 없이 값만 둔다 (0030_event.sql 주석).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "event_proposals")
public class EventProposal extends BaseEntity {

    public static final int CONTENT_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProposalStatus status = ProposalStatus.PENDING;

    public EventProposal(Event event, Long userId, String content) {
        this.event = event;
        this.userId = userId;
        this.content = Venue.requireText(content);
    }

    public void changeStatus(ProposalStatus status) {
        this.status = status;
    }
}
