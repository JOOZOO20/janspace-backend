package com.stagelog.setlist.domain;

import java.time.LocalDate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 자동 재생목록 동기화의 우선순위. 순서대로 ① 공연 당일·전날·다음 날 ② 앞으로 7일 안 ③ 나머지(지난 공연 포함).
 * ceilingPercent = 이 우선순위가 AUTO 예산에서 쓸 수 있는 누적 상한(%). 예산이 줄어들수록 낮은 우선순위부터 예약에 실패해 DEFERRED 가 된다.
 * 리셋 뒤 DEFERRED 재시도는 이 순서로 처리한다.
 * ponytail: 상한 비율(100/85/60)은 고정값이다. 실제 사용 분포가 쌓이면 설정으로 뺀다
 */
@Getter
@RequiredArgsConstructor
public enum PlaylistSyncPriority {
    SHOW_DAYS(100), WITHIN_WEEK(85), OTHERS(60);

    private final int ceilingPercent;

    // eventDate·today 는 같은 기준(행사 목록 시간대 Asia/Seoul)의 날짜
    public static PlaylistSyncPriority of(LocalDate eventDate, LocalDate today) {
        if (eventDate == null) {
            return OTHERS;
        }
        if (!eventDate.isBefore(today.minusDays(1)) && !eventDate.isAfter(today.plusDays(1))) {
            return SHOW_DAYS;
        }
        if (eventDate.isAfter(today) && !eventDate.isAfter(today.plusDays(7))) {
            return WITHIN_WEEK;
        }
        return OTHERS;
    }
}
