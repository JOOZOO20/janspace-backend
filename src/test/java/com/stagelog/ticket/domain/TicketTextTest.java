package com.stagelog.ticket.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TicketTextTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    static final LocalDate SHOW = LocalDate.of(2026, 9, 16);

    // 실제 티켓 문구를 본뜬 픽스처 (예매처별 전형적인 배치, 이름·예매번호는 가짜)
    static final List<String> INTERPARK = List.of(
            "INTERPARK TICKET",
            "잔나비 전국투어 [모든 소년 소녀들] - 서울",
            "일시 : 2026년 9월 16일(수) 오후 7시",
            "장소 : 올림픽공원 KSPO DOME",
            "1층 A구역 12열 5번",
            "R석 99,000원",
            "예매번호 T1234567890",
            "홍길동 님 1매");
    static final List<String> YES24 = List.of(
            "YES24 티켓",
            "2026.09.16 (수) 19:00",
            "KSPO돔",
            "VIP석 가구역 3열 10번");
    static final List<String> STANDING = List.of(
            "잔나비 소극장 콘서트",
            "2026-09-16 20:00",
            "스탠딩 B구역 정리번호 123",
            "고객센터 1544-1555");
    static final List<String> SINGAPORE = List.of(
            "JANNABI LIVE IN SINGAPORE",
            "Sat, Nov 14, 2026 8:00 PM",
            "The Star Theatre",
            "Section 105 Row 3 Seat 9",
            "Gate 3");
    static final List<String> JAPAN = List.of(
            "JANNABI JAPAN TOUR 2026",
            "２０２６年１２月５日(土) 開場17:00 開演18:00",
            "Zepp Haneda",
            "1階 A列 12番");
    // OCR 이 0 을 O 로, 1 을 l 로 읽은 경우
    static final List<String> OCR_NOISE = List.of(
            "SEOUL 2O26.O9.l6 (WED)",
            "Hall Row K Seat l2");

    @Nested
    @DisplayName("날짜")
    class Dates {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "2026.09.16|2026-09-16",
                "2026-09-16|2026-09-16",
                "2026/09/16|2026-09-16",
                "2026. 9. 16.|2026-09-16",
                "2026년 9월 16일|2026-09-16",
                "2026年9月16日|2026-09-16",
                "26.09.16|2026-09-16",
                "Sep 16, 2026|2026-09-16",
                "September 16th 2026|2026-09-16",
                "16 SEP 2026|2026-09-16",
                "16-Sep-2026|2026-09-16",
                "２０２６．０９．１６|2026-09-16"})
        void full_formats(String text, LocalDate expected) {
            assertThat(TicketText.dates(List.of("공연 " + text + " 19:00"), TODAY)).containsExactly(expected);
        }

        @Test
        @DisplayName("픽스처: 예매처별 티켓에서 공연 날짜 하나를 읽는다")
        void fixtures() {
            assertThat(TicketText.dates(INTERPARK, TODAY)).containsExactly(SHOW);
            assertThat(TicketText.dates(YES24, TODAY)).containsExactly(SHOW);
            assertThat(TicketText.dates(STANDING, TODAY)).containsExactly(SHOW);
            assertThat(TicketText.dates(SINGAPORE, TODAY)).containsExactly(LocalDate.of(2026, 11, 14));
            assertThat(TicketText.dates(JAPAN, TODAY)).containsExactly(LocalDate.of(2026, 12, 5));
        }

        @Test
        @DisplayName("OCR 오인식: 숫자 덩어리 안의 O·l 만 숫자로 고친다")
        void ocr_noise_in_digits() {
            assertThat(TicketText.dates(OCR_NOISE, TODAY)).containsExactly(SHOW);
            assertThat(TicketText.forNumbers("SEOUL Hall 2O26.O9.l6")).isEqualTo("SEOUL Hall 2026.09.16");
        }

        @Test
        @DisplayName("연도 없는 날짜: 요일이 있으면 요일이 맞는 가장 가까운 해")
        void yearless_with_weekday() {
            assertThat(TicketText.dates(List.of("09.16(수) 19:00"), TODAY)).containsExactly(SHOW);
            assertThat(TicketText.dates(List.of("9월 16일 (수)"), TODAY)).containsExactly(SHOW);
            // 12/25 가 수요일인 해 중 가장 가까운 해 = 2024 (오래된 티켓을 소급해 찍은 경우)
            assertThat(TicketText.dates(List.of("12.25(수)"), TODAY)).containsExactly(LocalDate.of(2024, 12, 25));
        }

        @Test
        @DisplayName("연도 없는 날짜: 요일이 없으면 오늘 기준 가까운 지난 날·다가올 날 둘 다 (가까운 쪽 먼저)")
        void yearless_without_weekday() {
            assertThat(TicketText.dates(List.of("9월 16일"), TODAY)).containsExactly(SHOW, LocalDate.of(2027, 9, 16));
            assertThat(TicketText.dates(List.of("Dec 25"), TODAY)).containsExactly(LocalDate.of(2026, 12, 25), LocalDate.of(2025, 12, 25));
        }

        @Test
        @DisplayName("연도 없는 날짜는 같은 티켓에 적힌 연도를 먼저 쓴다 (2026.09.16 ~ 09.18)")
        void yearless_uses_ticket_year() {
            assertThat(TicketText.dates(List.of("2026.09.16(수) ~ 09.18(금)"), LocalDate.of(2027, 3, 1)))
                    .containsExactly(SHOW, LocalDate.of(2026, 9, 18));
        }

        @Test
        @DisplayName("일/월 순서가 모호하면 둘 다 내고, 요일이 있으면 맞는 쪽만")
        void ambiguous_day_month() {
            assertThat(TicketText.dates(List.of("05/06/2026"), TODAY))
                    .containsExactlyInAnyOrder(LocalDate.of(2026, 6, 5), LocalDate.of(2026, 5, 6));
            assertThat(TicketText.dates(List.of("05/06/2026 (Wed)"), TODAY)).containsExactly(LocalDate.of(2026, 5, 6));
        }

        @Test
        @DisplayName("사흘 연속 공연: 티켓에 찍힌 그날만 읽는다")
        void consecutive_days() {
            assertThat(TicketText.dates(List.of("2026.09.17 (목) 19:00 KSPO DOME"), TODAY)).containsExactly(LocalDate.of(2026, 9, 17));
        }

        @Test
        @DisplayName("날짜가 아닌 숫자: 전화번호·가격·구역 번호·없는 날짜·한 자리 월.일 은 읽지 않는다")
        void not_dates() {
            assertThat(TicketText.dates(List.of("고객센터 02-1544-1555", "R석 99,000원", "Section 1-3", "2026.13.45", "3.5"), TODAY))
                    .isEmpty();
            assertThat(TicketText.dates(List.of(), TODAY)).isEmpty();
        }
    }

    @Nested
    @DisplayName("시각")
    class Times {

        @Test
        void formats() {
            assertThat(TicketText.times(List.of("19:00"))).contains(LocalTime.of(19, 0));
            assertThat(TicketText.times(List.of("오후 7시"))).containsExactly(LocalTime.of(19, 0));
            assertThat(TicketText.times(List.of("오후 7시 30분"))).containsExactly(LocalTime.of(19, 30));
            assertThat(TicketText.times(List.of("8:00 PM"))).containsExactly(LocalTime.of(20, 0));
            assertThat(TicketText.times(List.of("8PM"))).containsExactly(LocalTime.of(20, 0));
            assertThat(TicketText.times(List.of("開演18:00"))).containsExactly(LocalTime.of(18, 0));
            // 오전·오후 표시가 없는 1~11시는 둘 다
            assertThat(TicketText.times(List.of("7시"))).containsExactly(LocalTime.of(7, 0), LocalTime.of(19, 0));
            assertThat(TicketText.times(List.of("25:00", "2026.09.16"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("좌석")
    class Seats {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "1층 A구역 12열 5번|1층 A구역 12열 5번",
                "R석 3열 7번|R석 3열 7번",
                "스탠딩 B구역 정리번호 123|스탠딩 B구역 정리번호 123",
                "Row K Seat 12|Row K Seat 12",
                "Section 105 Row 3 Seat 9|Section 105 Row 3 Seat 9",
                "GATE: 3|GATE: 3",
                "Gate A|Gate A",
                "1 층 A 구역 12 열 5 번|1층 A구역 12열 5번",
                "1階 A列 12番|1階 A列 12番",
                "アリーナ A10ブロック 3列 12番|アリーナ A10ブロック 3列 12番",
                "整理番号 045|整理番号 045"})
        void formats(String line, String expected) {
            assertThat(TicketText.seat(List.of(line))).isEqualTo(expected);
        }

        @Test
        @DisplayName("픽스처: 여러 줄에서 좌석 조각만 모으고, 스텁에 같은 좌석이 다시 찍혀도 한 번만")
        void fixtures() {
            assertThat(TicketText.seat(INTERPARK)).isEqualTo("1층 A구역 12열 5번 R석");
            assertThat(TicketText.seat(YES24)).isEqualTo("VIP석 가구역 3열 10번");
            assertThat(TicketText.seat(STANDING)).isEqualTo("스탠딩 B구역 정리번호 123");
            assertThat(TicketText.seat(SINGAPORE)).isEqualTo("Section 105 Row 3 Seat 9 Gate 3");
            assertThat(TicketText.seat(JAPAN)).isEqualTo("1階 A列 12番");
            assertThat(TicketText.seat(OCR_NOISE)).isEqualTo("Row K Seat 12");
            assertThat(TicketText.seat(List.of("1층 A구역 12열 5번", "------", "1층 A구역 12열 5번"))).isEqualTo("1층 A구역 12열 5번");
        }

        @Test
        @DisplayName("좌석이 아닌 글자: 좌석이라는 낱말, 예매번호, 2번째, Seat No. 는 좌석이 아니다")
        void not_seats() {
            assertThat(TicketText.seat(List.of("좌석 안내", "예매번호 T1234", "잔나비 2번째 콘서트", "Seat No.", "SEOUL"))).isNull();
            assertThat(TicketText.seat(List.of())).isNull();
        }

        @Test
        @DisplayName("100자를 넘으면 자른다")
        void capped() {
            StringBuilder line = new StringBuilder();
            for (int i = 1; i <= 40; i++) {
                line.append(i).append("열 ");
            }
            assertThat(TicketText.seat(List.of(line.toString()))).startsWith("1열 2열").hasSizeLessThanOrEqualTo(Ticket.SEAT_MAX);
        }
    }
}
