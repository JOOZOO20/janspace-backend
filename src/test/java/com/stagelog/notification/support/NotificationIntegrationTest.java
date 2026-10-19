package com.stagelog.notification.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.stagelog.attendance.support.AttendanceIntegrationTest;
import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.notification.application.DispatchDuePushJobs;
import com.stagelog.notification.application.PushMessageRenderer;
import com.stagelog.notification.application.SchedulePushJobs;
import com.stagelog.notification.application.port.PushSenderPort;
import com.stagelog.notification.application.port.PushSenderPort.SendResult;
import com.stagelog.notification.domain.PushDelivery;
import com.stagelog.notification.domain.PushDeliveryRepository;
import com.stagelog.notification.domain.PushJob;
import com.stagelog.notification.domain.PushJobRepository;
import com.stagelog.notification.domain.PushKind;
import com.stagelog.notification.domain.PushSubscription;
import com.stagelog.notification.domain.PushSubscriptionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * notification 통합 테스트 기반. 전송(PushSenderPort)은 목, PushJobRepository 는 스파이(물질화 중단 시뮬레이션용),
 * PushMessageRenderer 는 스파이(배달 하나의 렌더링 실패 시뮬레이션용).
 * 디스패처는 runCycle(now) 로 직접 돌리며 now 는 행사 날짜(2026-10-23)보다 뒤인 NOW 다.
 */
public abstract class NotificationIntegrationTest extends AttendanceIntegrationTest {

    public static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    public static final SendResult OK = new SendResult(201, null);
    public static final SendResult UNAVAILABLE = new SendResult(503, "HTTP 503");

    @MockitoBean
    protected PushSenderPort sender;

    @MockitoSpyBean
    protected PushJobRepository jobs;

    @MockitoSpyBean
    protected PushMessageRenderer renderer;

    @Autowired
    protected PushDeliveryRepository deliveries;
    @Autowired
    protected PushSubscriptionRepository subscriptions;
    @Autowired
    protected SchedulePushJobs schedulePushJobs;
    @Autowired
    protected DispatchDuePushJobs dispatcher;

    @BeforeEach
    void cleanPushTables() {
        cleanPush();
    }

    @AfterEach
    void cleanPushAfter() {
        cleanPush();
    }

    private void cleanPush() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.execute("TRUNCATE TABLE push_deliveries");
        jdbc.execute("TRUNCATE TABLE push_jobs");
        jdbc.execute("TRUNCATE TABLE push_subscriptions");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    public static final long ADMIN_ID = 999L;

    // 관리자 principal. 쓰기 요청은 동의 게이트가 users 행을 조회하므로 insertUser(ADMIN_ID, null) 이 먼저 필요하다
    protected static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ADMIN_ID, true), null,
                List.of(new SimpleGrantedAuthority("ADMIN"))));
    }

    // 종료 시각(현지)과 시간대가 있는 행사. 잡 예약은 EventScheduleChanged 발행으로 따로 한다
    protected Event eventWithEnd(String name, LocalDate date, LocalDateTime endsAtLocal, String timezone) {
        Event event = newEvent(name, date);
        event.applySource(EventLockableField.TIMEZONE, timezone);
        event.applySource(EventLockableField.ENDS_AT, endsAtLocal);
        return eventRepository.save(event);
    }

    protected Event eventWithEnd(String name) {
        return eventWithEnd(name, DATE, DATE.atTime(21, 0), "Asia/Bangkok");
    }

    protected PushSubscription subscribe(long userId, String endpoint) {
        return subscriptions.save(PushSubscription.of(userId, endpoint, "p256dh-" + endpoint, "auth-" + endpoint, "test-ua"));
    }

    // 갈래요 행 (RECORD 대상)
    protected void plan(long userId, long eventId) {
        jdbc.update("INSERT INTO attendances (user_id, event_id, planned_at, created_at, updated_at) "
                + "VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", userId, eventId);
    }

    // 기록 행 (SETLIST_CONFIRMED 대상은 best_song_id 가 NULL 인 것)
    protected void recorded(long userId, long eventId, Long bestSongId) {
        jdbc.update("INSERT INTO attendances (user_id, event_id, attended_at, best_song_id, created_at, updated_at) "
                + "VALUES (?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", userId, eventId, bestSongId);
    }

    protected Optional<PushJob> job(long eventId, PushKind kind) {
        return jobs.findByEventIdAndKind(eventId, kind);
    }

    protected PushJob requireJob(long eventId, PushKind kind) {
        return job(eventId, kind).orElseThrow();
    }

    protected List<PushDelivery> deliveriesOf(PushJob job) {
        return deliveries.findAllByJobId(job.getId());
    }
}
