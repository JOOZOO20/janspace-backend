package com.stagelog.attendance.domain;

import com.stagelog.common.entity.SoftDeletableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유저 x 행사 한 행. 갈래요(plannedAt) -> 갔어요·기록(attendedAt) 으로 진행하며 삭제는 소프트, 재생성은 revive.
 * user_id·event_id 는 다른 기능의 엔티티라 ID 로만 든다. 별 밝기는 저장하지 않고 감정 유무로 계산한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "attendances")
public class Attendance extends SoftDeletableBaseEntity {

    public static final int MAX_EMOTIONS = 3;

    // DIM: 감정 없음(소급) / BRIGHT: 감정 있음 / BRIGHTEST: 감정 + 포토카드 연결
    public enum Brightness {
        DIM, BRIGHT, BRIGHTEST
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "planned_at", columnDefinition = "DATETIME(6)")
    private Instant plannedAt;

    @Column(name = "attended_at", columnDefinition = "DATETIME(6)")
    private Instant attendedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "emotion_1", length = 20)
    private Emotion emotion1;

    @Enumerated(EnumType.STRING)
    @Column(name = "emotion_2", length = 20)
    private Emotion emotion2;

    @Enumerated(EnumType.STRING)
    @Column(name = "emotion_3", length = 20)
    private Emotion emotion3;

    @Column(name = "best_song_id")
    private Long bestSongId;

    @Column(length = 500)
    private String memo;

    @Embedded
    private DepartureSnapshot departure;

    @Column(name = "continued_from_previous", nullable = false)
    private boolean continuedFromPrevious;

    @Column(name = "distance_km", precision = 8, scale = 1)
    private BigDecimal distanceKm;

    private Attendance(Long userId, Long eventId) {
        this.userId = Objects.requireNonNull(userId);
        this.eventId = Objects.requireNonNull(eventId);
    }

    public static Attendance of(Long userId, Long eventId) {
        return new Attendance(userId, eventId);
    }

    // 갈래요. 이미 눌렀으면 시각을 유지한다
    public void plan(DepartureSnapshot departure, Instant now) {
        if (plannedAt == null) {
            plannedAt = now;
        }
        this.departure = departure;
    }

    // 갔어요·기록. 갈래요 행이면 그 위에서 진행한다. 감정 검증은 유스케이스가 한다
    public void attend(List<Emotion> emotions, Long songId, String memo, DepartureSnapshot departure,
                       boolean continuedFromPrevious, Instant now) {
        this.attendedAt = now;
        setEmotions(emotions);
        this.bestSongId = songId;
        this.memo = memo;
        this.departure = departure;
        this.continuedFromPrevious = continuedFromPrevious;
    }

    // 기록 수정 (PATCH). 소급 별([] )에 감정을 넣으면 밝아진다. 검증은 유스케이스가 한다
    public void record(List<Emotion> emotions) {
        setEmotions(emotions);
    }

    public void changeSong(Long songId) {
        this.bestSongId = songId;
    }

    public void changeMemo(String memo) {
        this.memo = memo;
    }

    // 소프트 삭제 행 되살리기: 삭제 표시와 진행 상태를 비운다. 호출자가 plan/attend 로 다시 채운다
    public void revive() {
        super.delete(null);
        plannedAt = null;
        attendedAt = null;
        setEmotions(List.of());
        bestSongId = null;
        memo = null;
        departure = null;
        continuedFromPrevious = false;
        distanceKm = null;
    }

    public void softDelete() {
        super.delete(Instant.now());
    }

    public void updateDistance(Double km) {
        this.distanceKm = km == null ? null : BigDecimal.valueOf(km).setScale(1, RoundingMode.HALF_UP);
    }

    public boolean isStar() {
        return attendedAt != null;
    }

    public boolean isRetro() {
        return isStar() && emotion1 == null;
    }

    public Brightness brightness() {
        return brightness(false);
    }

    // 포토카드 연결 여부는 photocard 기능이 알므로 밖에서 받는다
    public Brightness brightness(boolean photocardLinked) {
        if (emotion1 == null) {
            return Brightness.DIM;
        }
        return photocardLinked ? Brightness.BRIGHTEST : Brightness.BRIGHT;
    }

    public List<Emotion> emotions() {
        List<Emotion> out = new ArrayList<>(MAX_EMOTIONS);
        for (Emotion e : new Emotion[] {emotion1, emotion2, emotion3}) {
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    private void setEmotions(List<Emotion> emotions) {
        emotion1 = emotions.size() > 0 ? emotions.get(0) : null;
        emotion2 = emotions.size() > 1 ? emotions.get(1) : null;
        emotion3 = emotions.size() > 2 ? emotions.get(2) : null;
    }
}
