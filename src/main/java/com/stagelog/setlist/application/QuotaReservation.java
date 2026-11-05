package com.stagelog.setlist.application;

import com.stagelog.setlist.domain.QuotaBucket;
import com.stagelog.setlist.domain.QuotaOperation;
import java.time.LocalDate;
import java.util.List;

/**
 * 한 번에 예약한 호출 묶음. entries 의 순서는 예약할 때 넘긴 연산 순서와 같다. 확정·반환은 예약한 날짜 행에 한다(호출 중 자정을 넘겨도).
 */
public record QuotaReservation(LocalDate date, QuotaBucket bucket, List<Entry> entries) {

    public record Entry(Long ledgerId, QuotaOperation operation) {
    }

    public Entry entry(int index) {
        return entries.get(index);
    }
}
