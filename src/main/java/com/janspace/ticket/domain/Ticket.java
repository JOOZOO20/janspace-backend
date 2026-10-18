package com.janspace.ticket.domain;

import com.janspace.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 티켓북의 티켓 한 장 (사용자 x 행사 1장). 공연명·공연장·시각은 행사 값을 쓰므로 좌석과 색만 든다.
 * user_id·event_id·skin_id 는 ID 로만 든다. 사진·예매자 정보를 담지 않고 되살릴 일도 없어 물리 삭제하므로 BaseEntity 만 상속한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "tickets")
public class Ticket extends BaseEntity {

    public static final int SEAT_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(length = SEAT_MAX)
    private String seat;

    @Column(name = "accent_color", length = 7)
    private String accentColor;

    @Column(name = "skin_id")
    private Long skinId;

    public Ticket(Long userId, Long eventId, String seat, String accentColor, Long skinId) {
        this.userId = userId;
        this.eventId = eventId;
        changeSeat(seat);
        changeAccent(accentColor);
        this.skinId = skinId;
    }

    public void moveTo(Long eventId) {
        this.eventId = eventId;
    }

    // 공백만 있으면 좌석 없음
    public void changeSeat(String seat) {
        this.seat = seat == null || seat.isBlank() ? null : seat.trim();
    }

    // #RRGGBB 형식 검증은 API 입력에서 한다. 저장은 소문자로 통일
    public void changeAccent(String accentColor) {
        this.accentColor = accentColor == null ? null : accentColor.toLowerCase(Locale.ROOT);
    }

    public void changeSkin(Long skinId) {
        this.skinId = skinId;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
