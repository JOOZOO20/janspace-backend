package com.janspace.ticket.domain;

import com.janspace.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 티켓 스킨(종이색·포인트색·글자색). 시즌 스킨은 배포 기간 안에서만 공개 목록에 나오고, 기본 스킨은 기간과 무관하게 항상 나온다.
 * 관리자 마스터 데이터라 BaseEntity 만 상속하고, 지우면 그 스킨을 쓰던 티켓은 FK(ON DELETE SET NULL)로 기본 스킨이 된다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "ticket_skins")
public class TicketSkin extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String season;

    @Column(name = "paper_color", nullable = false, length = 7)
    private String paperColor;

    @Column(name = "accent_color", nullable = false, length = 7)
    private String accentColor;

    @Column(name = "ink_color", nullable = false, length = 7)
    private String inkColor;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Column(name = "available_until")
    private LocalDate availableUntil;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    public static TicketSkin create() {
        return new TicketSkin();
    }

    // 색 형식(#RRGGBB)은 API 입력에서 검증한다. 저장은 소문자로 통일
    public void update(String name, String season, String paperColor, String accentColor, String inkColor,
                       LocalDate availableFrom, LocalDate availableUntil) {
        this.name = name.trim();
        this.season = season == null || season.isBlank() ? null : season.trim();
        this.paperColor = paperColor.toLowerCase(Locale.ROOT);
        this.accentColor = accentColor.toLowerCase(Locale.ROOT);
        this.inkColor = inkColor.toLowerCase(Locale.ROOT);
        this.availableFrom = availableFrom;
        this.availableUntil = availableUntil;
    }

    public void markDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    // 공개 목록 조건: 기본 스킨이거나 from <= today <= until (NULL 은 열린 쪽)
    public boolean isListedOn(LocalDate today) {
        return isDefault || isSeasonActiveOn(today);
    }

    // 새 티켓에 자동으로 입힐 시즌 스킨인가 (기본 스킨 제외)
    public boolean isSeasonActiveOn(LocalDate today) {
        return !isDefault && (availableFrom == null || !today.isBefore(availableFrom))
                && (availableUntil == null || !today.isAfter(availableUntil));
    }
}
