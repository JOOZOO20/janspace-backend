package com.stagelog.setlist.application;

import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.setlist.domain.ApiQuotaDailyRepository;
import com.stagelog.setlist.domain.ApiQuotaLedger;
import com.stagelog.setlist.domain.ApiQuotaLedgerRepository;
import com.stagelog.setlist.domain.QuotaBucket;
import com.stagelog.setlist.domain.QuotaEntryStatus;
import com.stagelog.setlist.domain.QuotaOperation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * YouTube Data API Quota 원장. 호출 전에 예약하고(한도를 넘으면 실패), 응답을 받으면 사용으로 확정, 결과를 모르면 UNKNOWN(사용으로 계산), 호출하지 않은 몫은 반환한다.
 * 날짜는 리셋 기준인 태평양 시간 자정으로 자른다 (https://developers.google.com/youtube/v3/determine_quota_cost "Daily quotas reset at midnight Pacific Time").
 * 각 메서드는 REQUIRES_NEW 로 바로 커밋한다. 호출자 트랜잭션(재생목록 확보의 페이지 행 잠금)에 묶이면 일일 행 잠금을 그만큼 오래 잡아 다른 행사의 예약이 기다린다.
 * 예산: AUTO 8,000, MANUAL 1,000 (설정값). 기본 일일 한도 10,000 중 남는 1,000 은 어느 bucket 에도 주지 않는 여유분이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeQuota {

    public static final String PROVIDER = "YOUTUBE";
    public static final ZoneId RESET_ZONE = ZoneId.of("America/Los_Angeles");
    static final int TOP_EVENTS = 10;

    private final ApiQuotaDailyRepository days;
    private final ApiQuotaLedgerRepository ledger;
    private final OperatorAlert operatorAlert;
    // 생성자 주입 대상이 아니라 final 이 아니다 (@RequiredArgsConstructor 가 @Value 를 옮기지 않는다)
    @Value("${jan.youtube.quota.auto-units:8000}")
    private int autoUnits;
    @Value("${jan.youtube.quota.manual-units:1000}")
    private int manualUnits;

    // DB 시계 기준 태평양 날짜
    public LocalDate today() {
        return Instant.ofEpochMilli(days.dbNowEpochMillis()).atZone(RESET_ZONE).toLocalDate();
    }

    // 오늘·내일 일일 행을 미리 만든다 (리셋 직후 스케줄러와 기동 시). 있는 행은 그대로 둔다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepareDays() {
        LocalDate today = today();
        for (LocalDate date : List.of(today, today.plusDays(1))) {
            int created = days.createDay(PROVIDER, date, QuotaBucket.AUTO.name(), autoUnits)
                    + days.createDay(PROVIDER, date, QuotaBucket.MANUAL.name(), manualUnits);
            if (created > 0) {
                log.info("[유튜브 할당량 일일 행 생성] date={}, rows={}, auto={}, manual={}", date, created, autoUnits, manualUnits);
            }
        }
    }

    // 연산 묶음의 단가 합계를 한 번에 예약한다. 실패하면 429 YOUTUBE_QUOTA_EXHAUSTED 이고 호출자는 연산을 시작하지 않는다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuotaReservation reserve(QuotaScope scope, List<QuotaOperation> operations) {
        LocalDate date = today();
        int units = operations.stream().mapToInt(QuotaOperation::getUnits).sum();
        if (days.reserve(PROVIDER, date, scope.bucket().name(), units, scope.ceilingPercent()) == 0) {
            log.warn("[유튜브 할당량 예약 실패] date={}, bucket={}, units={}, ceilingPercent={}, eventId={}",
                    date, scope.bucket(), units, scope.ceilingPercent(), scope.eventId());
            throw new ApplicationException(ErrorCode.YOUTUBE_QUOTA_EXHAUSTED);
        }
        List<ApiQuotaLedger> rows = ledger.saveAll(operations.stream()
                .map(op -> ApiQuotaLedger.reserve(PROVIDER, date, scope.bucket(), op, scope.eventId(), scope.syncRunId())).toList());
        return new QuotaReservation(date, scope.bucket(), rows.stream()
                .map(row -> new QuotaReservation.Entry(row.getId(), row.getOperation())).toList());
    }

    // 응답을 받았으면(성공·실패 응답 모두) CONSUMED, 결과를 모르면(타임아웃·연결 끊김) UNKNOWN. 둘 다 사용으로 계산한다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(QuotaReservation reservation, int index, boolean responded) {
        QuotaReservation.Entry entry = reservation.entry(index);
        QuotaEntryStatus status = responded ? QuotaEntryStatus.CONSUMED : QuotaEntryStatus.UNKNOWN;
        if (ledger.finish(entry.ledgerId(), status.name()) == 1) {
            days.consume(PROVIDER, reservation.date(), reservation.bucket().name(), entry.operation().getUnits());
        }
    }

    // 아직 RESERVED 인 몫(호출하지 않은 연산)을 돌려놓는다. 확정된 행은 건너뛴다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(QuotaReservation reservation) {
        int released = 0;
        for (QuotaReservation.Entry entry : reservation.entries()) {
            if (ledger.finish(entry.ledgerId(), QuotaEntryStatus.RELEASED.name()) == 1) {
                released += entry.operation().getUnits();
            }
        }
        if (released > 0) {
            days.release(PROVIDER, reservation.date(), reservation.bucket().name(), released);
        }
    }

    // API 가 quotaExceeded 를 돌려줬다: 그날 모든 bucket 을 막고, 처음 막은 호출만 운영자에게 알린다. 재시도하지 않는다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void exhaust(LocalDate date) {
        if (days.exhaust(PROVIDER, date) > 0) {
            log.warn("[유튜브 할당량 소진] quotaExceeded 를 받아 쓰기를 멈춥니다 date={}", date);
            operatorAlert.send("[유튜브 할당량 소진] YouTube Data API 가 quotaExceeded 를 돌려줬습니다. 태평양 날짜 " + date
                    + " 의 재생목록 동기화를 모두 멈추고, 미룬 동기화는 다음 리셋(태평양 시간 자정) 뒤 다시 돌립니다.");
        }
    }

    @Transactional(readOnly = true)
    public QuotaUsage usage(LocalDate date) {
        List<QuotaUsage.Bucket> buckets = days.findAllByIdProviderAndIdQuotaDateOrderByIdBucket(PROVIDER, date).stream()
                .map(d -> new QuotaUsage.Bucket(d.getId().getBucket().name(), d.getLimitUnits(), d.getUsedUnits(), d.getReservedUnits(),
                        d.getExhaustedAt()))
                .toList();
        List<QuotaUsage.EventUsage> top = ledger.topEvents(PROVIDER, date, TOP_EVENTS).stream()
                .map(e -> new QuotaUsage.EventUsage(e.getEventId(), e.getUnits())).toList();
        return new QuotaUsage(date, buckets, top);
    }
}
