package com.janspace.event.domain;

import com.janspace.common.entity.BaseEntity;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.locale.SupportedLocale;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공연장. 좌표는 "최초 채움(출처·지오코딩)은 자동, 이후에는 관리자만" 이며 관리자 수정은 adminUpdate 로 값 변경과 잠금을 함께 한다.
 * 공연장은 이동하지 않으므로 소프트 삭제 대상이 아니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "venues")
public class Venue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Convert(converter = SupportedLocaleConverter.class)
    @Column(name = "original_locale", nullable = false, length = 10)
    private SupportedLocale originalLocale;

    @Column(length = 255)
    private String city;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(length = 64)
    private String timezone;

    @Convert(converter = VenueLockedFieldsConverter.class)
    @Column(name = "locked_fields", nullable = false, columnDefinition = "json")
    private Set<VenueLockableField> lockedFields = EnumSet.noneOf(VenueLockableField.class);

    private Venue(String name, SupportedLocale originalLocale, String city, String countryCode) {
        this.name = requireText(name);
        this.originalLocale = Objects.requireNonNull(originalLocale);
        this.city = city;
        this.countryCode = countryCode == null ? null : countryCode.toUpperCase();
    }

    public static Venue create(String name, SupportedLocale originalLocale, String city, String countryCode) {
        return new Venue(name, originalLocale, city, countryCode);
    }

    public Set<VenueLockableField> getLockedFields() {
        return Collections.unmodifiableSet(lockedFields);
    }

    public boolean isLocked(VenueLockableField field) {
        return lockedFields.contains(field);
    }

    public GeoPoint coordinates() {
        return GeoPoint.of(lat, lng);
    }

    // 관리자 수정: 값 변경 + 잠금을 한 번에. 값이 실제로 바뀌었으면 true
    public boolean adminUpdate(VenueLockableField field, Object value) {
        boolean changed = apply(field, value);
        lockedFields.add(field);
        return changed;
    }

    public void unlock(VenueLockableField field) {
        lockedFields.remove(field);
    }

    // 좌표 최초 채움. 이미 있거나 잠겨 있으면 무시
    public boolean fillCoordinates(GeoPoint point) {
        if (point == null || lat != null || lng != null || isLocked(VenueLockableField.COORDINATES)) {
            return false;
        }
        return apply(VenueLockableField.COORDINATES, point);
    }

    public boolean fillTimezone(String zone) {
        if (zone == null || timezone != null || isLocked(VenueLockableField.TIMEZONE)) {
            return false;
        }
        return apply(VenueLockableField.TIMEZONE, zone);
    }

    // 도시·국가 빈칸 채움 (비민감, 잠금 없는 필드만)
    public void fillDetails(String city, String countryCode) {
        if (this.city == null && city != null && !isLocked(VenueLockableField.CITY)) {
            this.city = city;
        }
        if (this.countryCode == null && countryCode != null) {
            this.countryCode = countryCode.toUpperCase();
        }
    }

    private boolean apply(VenueLockableField field, Object value) {
        switch (field) {
            case NAME -> {
                String next = requireText((String) value);
                boolean changed = !next.equals(name);
                name = next;
                return changed;
            }
            case CITY -> {
                String next = (String) value;
                boolean changed = !Objects.equals(next, city);
                city = next;
                return changed;
            }
            case COORDINATES -> {
                GeoPoint next = (GeoPoint) value;
                BigDecimal nextLat = next == null ? null : next.latDecimal();
                BigDecimal nextLng = next == null ? null : next.lngDecimal();
                boolean changed = !Objects.equals(nextLat, lat) || !Objects.equals(nextLng, lng);
                lat = nextLat;
                lng = nextLng;
                return changed;
            }
            case TIMEZONE -> {
                String next = requireZone((String) value);
                boolean changed = !Objects.equals(next, timezone);
                timezone = next;
                return changed;
            }
        }
        throw new IllegalArgumentException("알 수 없는 필드: " + field);
    }

    static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "이름은 비어 있을 수 없습니다.");
        }
        return value.trim();
    }

    static String requireZone(String zone) {
        if (zone == null) {
            return null;
        }
        try {
            return ZoneId.of(zone).getId();
        } catch (DateTimeException e) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "알 수 없는 시간대입니다: " + zone);
        }
    }
}
