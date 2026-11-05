package com.stagelog.event.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.EventView;
import com.stagelog.event.application.EventView.VenueView;
import com.stagelog.event.domain.EventStatus;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.event.domain.Provider;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 프론트 Event 계약: 파생 status, HH:mm, LocalizedText 키.
 */
class EventResponseTest {

    static final LocalDate DATE = LocalDate.of(2026, 10, 23);

    static EventView view(EventStatus status, VenueView venue, LocalDateTime ends) {
        return new EventView(1L, new LocalizedText("스타더스트 클럽", Map.of(SupportedLocale.EN, "Stardust Club")), EventType.CONCERT, status,
                DATE, DATE.atTime(19, 0), ends, "Asia/Seoul", venue, null, Map.of(Provider.KOPIS, "PF1"), null, null, null,
                new LocalizedText("출연: 스타더스트 클럽", Map.of(SupportedLocale.EN, "Cast: STARDUST CLUB")), 150, Set.of());
    }

    static VenueView venue(GeoPoint point) {
        return new VenueView(3L, LocalizedText.of("KSPO DOME"), LocalizedText.of("서울"), "KR", point, "Asia/Seoul");
    }

    @Test
    @DisplayName("status: 취소 계열은 그대로, 그 외는 공연장 좌표 + 종료 시각이 모두 있어야 COMPLETE")
    void status_derivation() {
        assertThat(EventResponse.from(view(EventStatus.ACTIVE, venue(new GeoPoint(37.5, 127.1)), DATE.atTime(21, 0))).status()).isEqualTo("COMPLETE");
        assertThat(EventResponse.from(view(EventStatus.ACTIVE, venue(new GeoPoint(37.5, 127.1)), null)).status()).isEqualTo("INCOMPLETE");
        assertThat(EventResponse.from(view(EventStatus.ACTIVE, venue(null), DATE.atTime(21, 0))).status()).isEqualTo("INCOMPLETE");
        assertThat(EventResponse.from(view(EventStatus.ACTIVE, null, DATE.atTime(21, 0))).status()).isEqualTo("INCOMPLETE");
        assertThat(EventResponse.from(view(EventStatus.CANCEL_SUSPECTED, venue(new GeoPoint(37.5, 127.1)), DATE.atTime(21, 0))).status()).isEqualTo("CANCEL_SUSPECTED");
        assertThat(EventResponse.from(view(EventStatus.CANCELLED, null, null)).status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("JSON: date yyyy-MM-dd, startTime HH:mm, name·description 은 original + 번역 키, venue.id, source 는 제공자별 null 허용, starCount 0 / my null")
    void json_shape() {
        String json = JsonMapper.builder().build().writeValueAsString(
                EventResponse.from(view(EventStatus.ACTIVE, venue(new GeoPoint(37.5, 127.1)), DATE.atTime(21, 30))));

        assertThat(json).contains("\"date\":\"2026-10-23\"")
                .contains("\"startTime\":\"19:00\"")
                .contains("\"endTime\":\"21:30\"")
                .contains("\"timezone\":\"Asia/Seoul\"")
                .contains("\"name\":{\"original\":\"스타더스트 클럽\",\"en\":\"Stardust Club\"}")
                .contains("\"kind\":\"CONCERT\"")
                .contains("\"kopisId\":\"PF1\"")
                .contains("\"naverScheduleId\":null")
                .contains("\"location\":{\"lat\":37.5,\"lng\":127.1}")
                .contains("\"venue\":{\"id\":3,")
                .contains("\"description\":{\"original\":\"출연: 스타더스트 클럽\",\"en\":\"Cast: STARDUST CLUB\"}")
                .contains("\"runningMinutes\":150")
                .contains("\"playlistUrl\":null")
                .contains("\"starCount\":0")
                .contains("\"my\":null");
    }
}
