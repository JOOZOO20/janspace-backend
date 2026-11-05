package com.stagelog.event.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.VenueCommand.VenueInput;
import com.stagelog.event.application.event.EventScheduleChanged;
import com.stagelog.event.application.event.EventVenueChanged;
import com.stagelog.event.application.port.PosterStoragePort;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventStatus;
import com.stagelog.event.domain.EventTranslation;
import com.stagelog.event.domain.EventTranslationRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.TranslationField;
import com.stagelog.event.domain.TranslationSource;
import com.stagelog.event.domain.Venue;
import com.stagelog.ops.application.AdminAuditRecorder;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.ops.application.StorageCleanup;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 행사 생성·수정. 관리자가 준 필드는 전부 adminUpdate 로 들어가 잠기고, 잠금 해제는 곧바로 출처 접기를 다시 실행한다.
 * 일정 필드나 공연 시간 추정에 쓰는 값(공연 시간·시즌·종류)이 바뀌면 EventScheduleChanged, 공연장이 바뀌면 EventVenueChanged 를 발행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventCommand {

    // 관리자 입력 원어는 한국어로 본다 (기획: 한국어 완성 -> 다국어). 다른 원어가 필요하면 번역 행으로 표현한다
    static final SupportedLocale ADMIN_LOCALE = SupportedLocale.KO;
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    // 생성자 주입 대상이 아니라 final 이 아니다 (@RequiredArgsConstructor 가 @Value 를 옮기지 않는다)
    @Value("${jan.storage.versioned-key.enabled:true}")
    private boolean versionedKey;

    private final EventRepository eventRepository;
    private final EventTranslationRepository translationRepository;
    private final VenueCommand venueCommand;
    private final ApplySourcesUseCase applySourcesUseCase;
    private final TranslationDrafts translationDrafts;
    private final PosterStoragePort posterStoragePort;
    private final StorageCleanup storageCleanup;
    private final RunningTimeBorrowers runningTimeBorrowers;
    private final ApplicationEventPublisher publisher;
    private final AdminAuditRecorder audit;

    /**
     * 관리자 입력. 생성은 name·kind·date 필수, 수정은 null 필드를 "변경 없음"으로 본다 (venue null 도 변경 없음).
     * 출처도 채우는 ticketUrl·description·runningMinutes 는 null = 변경 없음, Optional.empty() = 관리자 값을 지우고 출처 값 따르기,
     * 값 = 관리자 고정(잠금).
     */
    public record EventInput(LocalizedText name, EventType kind, LocalDate date, LocalTime startTime, LocalTime endTime,
                             VenueInput venue, String season, EventStatus status, Optional<String> ticketUrl,
                             Optional<LocalizedText> description, Optional<Integer> runningMinutes) {
    }

    @Transactional
    public Long create(EventInput input, AuditActor actor) {
        if (input.name() == null || input.kind() == null || input.date() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "name, kind, date 는 필수입니다.");
        }
        // 1. 생성 후 관리자 값을 전부 잠금과 함께 적용
        Event event = Event.create(input.name().original(), ADMIN_LOCALE, input.kind(), input.date());
        event.adminUpdate(EventLockableField.NAME, input.name().original());
        event.adminUpdate(EventLockableField.TYPE, input.kind());
        event.adminUpdate(EventLockableField.EVENT_DATE, input.date());
        applyOptional(event, input);
        eventRepository.save(event);
        // 2. 출처도 채우는 필드(설명 번역은 행사 ID 가 필요해 저장 뒤). 새 행사라 지울 관리자 값은 없다
        applySourced(event, input);
        // 3. 이름 번역: 관리자 번역 또는 기계 초안
        saveNameTranslations(event, input.name());
        // 4. 새 행사의 일정은 알림 예약 대상. 공연 시간이 있으면 같은 시즌 회차의 추정치도 바뀐다
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        if (event.getRunningMinutes() != null) {
            runningTimeBorrowers.republish(event.getSeason(), event.getId());
        }
        if (event.getVenue() != null) {
            publisher.publishEvent(new EventVenueChanged(event.getId()));
        }
        LockAudit.record(audit, actor, LockAudit.EVENT, event.getId(), Set.of(), event.getLockedFields());
        log.info("[행사 생성] eventId={}, date={}, venueId={}", event.getId(), event.getEventDate(), event.venueId());
        return event.getId();
    }

    /**
     * 출처 스냅샷으로 행사를 만든다 (네이버 캘린더 자동 등록). 관리자 잠금 없이 만들고 곧바로 접기까지 하므로
     * 이후 동기화가 이름·날짜를 계속 따라간다. 종류는 알 수 없어 CONCERT 로 두고 관리자가 고친다.
     */
    @Transactional
    public Long createFromSource(Provider provider, SourceSnapshot snapshot) {
        String name = snapshot.name() == null || snapshot.name().isBlank() ? "(이름 없음)" : snapshot.name();
        LocalDate date = snapshot.eventDate() != null ? snapshot.eventDate()
                : LocalDate.parse(snapshot.occurrenceKey().substring(0, 10));
        Event event = eventRepository.save(Event.create(name, ADMIN_LOCALE, EventType.CONCERT, date));
        applySourcesUseCase.apply(event.getId(), Map.of(provider, snapshot));
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        log.info("[행사 출처 생성] eventId={}, provider={}, externalId={}, key={}", event.getId(), provider,
                snapshot.externalId(), snapshot.occurrenceKey());
        return event.getId();
    }

    // 제공자가 준 번역(예: Ticketmaster 영문 행사명). 관리자·검토된 번역은 덮어쓰지 않는다
    @Transactional
    public void upsertProviderTranslation(Long eventId, TranslationField field, SupportedLocale locale, String text) {
        Event event = load(eventId);
        translationRepository.findByEventIdAndFieldAndLocale(eventId, field, locale).ifPresentOrElse(
                existing -> {
                    if (existing.getSource() != TranslationSource.ADMIN && !existing.isReviewed()) {
                        existing.update(text, TranslationSource.PROVIDER, false);
                    }
                },
                () -> translationRepository.save(new EventTranslation(event, field, locale, text, TranslationSource.PROVIDER, false)));
    }

    @Transactional
    public void update(Long eventId, EventInput input, AuditActor actor) {
        Event event = load(eventId);
        Set<EventLockableField> lockedBefore = Set.copyOf(event.getLockedFields());
        boolean scheduleChanged = false;
        boolean venueChanged = false;
        // 공연 시간 추정(RunningTime)에 쓰는 값. 이 행사와 같은 시즌 회차의 기록 알림 시각이 이 값을 따른다
        Integer minutesBefore = event.getRunningMinutes();
        String seasonBefore = event.getSeason();
        EventType typeBefore = event.getType();
        LocalDate dateBefore = event.getEventDate();
        if (input.name() != null) {
            event.adminUpdate(EventLockableField.NAME, input.name().original());
            input.name().translations().forEach((locale, text) ->
                    upsertTranslation(event, TranslationField.NAME, locale, text, TranslationSource.ADMIN, true));
        }
        if (input.kind() != null) {
            event.adminUpdate(EventLockableField.TYPE, input.kind());
        }
        if (input.date() != null) {
            scheduleChanged |= event.adminUpdate(EventLockableField.EVENT_DATE, input.date());
        }
        if (input.startTime() != null) {
            scheduleChanged |= event.adminUpdate(EventLockableField.STARTS_AT, LocalDateTime.of(event.getEventDate(), input.startTime()));
        }
        if (input.endTime() != null) {
            scheduleChanged |= event.adminUpdate(EventLockableField.ENDS_AT, LocalDateTime.of(event.getEventDate(), input.endTime()));
        }
        if (input.venue() != null) {
            Venue venue = venueCommand.createOrReuse(input.venue());
            venueChanged |= event.adminUpdate(EventLockableField.VENUE, venue);
            scheduleChanged |= VenueCommand.syncTimezoneFromVenue(event);
        }
        if (input.season() != null) {
            event.adminUpdate(EventLockableField.SEASON, input.season());
        }
        if (input.status() != null && event.changeStatus(input.status()) && input.status() == EventStatus.CANCELLED) {
            scheduleChanged = true;
        }
        // 관리자 값을 지운 필드는 곧바로 출처 값으로 다시 접는다 (잠금 해제와 같은 흐름)
        if (applySourced(event, input)) {
            applySourcesUseCase.refold(eventId);
        }
        boolean runningTimeChanged = !Objects.equals(minutesBefore, event.getRunningMinutes())
                || !Objects.equals(seasonBefore, event.getSeason()) || typeBefore != event.getType();
        if (scheduleChanged || runningTimeChanged) {
            publisher.publishEvent(new EventScheduleChanged(eventId));
        }
        // 빌려 줄 값이나 "가장 가까운 회차"가 바뀌었을 수 있다. 시즌을 옮겼으면 옛 시즌 회차도
        if (runningTimeChanged || !dateBefore.equals(event.getEventDate())) {
            runningTimeBorrowers.republish(seasonBefore, eventId);
            if (!Objects.equals(seasonBefore, event.getSeason())) {
                runningTimeBorrowers.republish(event.getSeason(), eventId);
            }
        }
        if (venueChanged) {
            publisher.publishEvent(new EventVenueChanged(eventId));
        }
        LockAudit.record(audit, actor, LockAudit.EVENT, eventId, lockedBefore, event.getLockedFields());
        log.info("[행사 관리자 수정] eventId={}, locked={}", eventId, event.getLockedFields());
    }

    // 잠금 해제 = 출처 값 따르기. 해제 즉시 접기를 다시 실행한다
    @Transactional
    public void unlock(Long eventId, EventLockableField field, AuditActor actor) {
        Event event = load(eventId);
        Set<EventLockableField> lockedBefore = Set.copyOf(event.getLockedFields());
        event.unlock(field);
        LockAudit.record(audit, actor, LockAudit.EVENT, eventId, lockedBefore, event.getLockedFields());
        applySourcesUseCase.refold(eventId);
        log.info("[행사 잠금 해제] eventId={}, field={}", eventId, field);
    }

    @Transactional
    public void upsertTranslation(Long eventId, TranslationField field, SupportedLocale locale, String text, boolean reviewed) {
        upsertTranslation(load(eventId), field, locale, text, TranslationSource.ADMIN, reviewed);
    }

    // 포스터 업로드 -> S3 -> POSTER 잠금. 공개 URL 을 돌려준다. 버전 key 는 내용이 바뀌면 URL 도 바뀌고, 이전 key 는 7일 뒤 삭제 예약
    @Transactional
    public String uploadPoster(Long eventId, byte[] bytes, String contentType, AuditActor actor) {
        String extension = contentType == null ? null : IMAGE_EXTENSIONS.get(contentType.toLowerCase());
        if (extension == null || bytes == null || bytes.length == 0) {
            throw new BadRequestException(ErrorCode.POSTER_INVALID);
        }
        Event event = load(eventId);
        String oldKey = event.getPosterKey();
        // 외부 호출 (S3). 버전 key 는 같은 내용이면 PUT 을 건너뛴다
        String key = versionedKey
                ? posterStoragePort.putVersioned(PosterKeys.versionedPrefix(eventId), extension, bytes, contentType)
                : posterStoragePort.put(PosterKeys.fixedKey(eventId, extension), bytes, contentType);
        Set<EventLockableField> lockedBefore = Set.copyOf(event.getLockedFields());
        event.adminUpdate(EventLockableField.POSTER, key);
        LockAudit.record(audit, actor, LockAudit.EVENT, eventId, lockedBefore, event.getLockedFields());
        storageCleanup.scheduleReplaced(oldKey, key);
        log.info("[포스터 업로드] eventId={}, key={}, bytes={}", eventId, key, bytes.length);
        return posterStoragePort.publicUrl(key);
    }

    private void applyOptional(Event event, EventInput input) {
        if (input.startTime() != null) {
            event.adminUpdate(EventLockableField.STARTS_AT, LocalDateTime.of(event.getEventDate(), input.startTime()));
        }
        if (input.endTime() != null) {
            event.adminUpdate(EventLockableField.ENDS_AT, LocalDateTime.of(event.getEventDate(), input.endTime()));
        }
        if (input.season() != null) {
            event.adminUpdate(EventLockableField.SEASON, input.season());
        }
        if (input.status() != null) {
            event.changeStatus(input.status());
        }
        if (input.venue() != null) {
            event.adminUpdate(EventLockableField.VENUE, venueCommand.createOrReuse(input.venue()));
            VenueCommand.syncTimezoneFromVenue(event);
        }
    }

    // 출처도 채우는 필드: null = 변경 없음, empty = 관리자 값 지우기, 값 = 관리자 고정. 실제로 지운 필드가 있으면 true (접기 재실행 대상)
    private boolean applySourced(Event event, EventInput input) {
        boolean cleared = applySourced(event, EventLockableField.TICKET_URL, input.ticketUrl());
        cleared |= applySourced(event, EventLockableField.RUNNING_MINUTES, input.runningMinutes());
        if (input.description() == null) {
            return cleared;
        }
        if (input.description().isPresent()) {
            LocalizedText description = input.description().get();
            event.adminUpdate(EventLockableField.DESCRIPTION, description.original().trim());
            description.translations().forEach((locale, text) ->
                    upsertTranslation(event, TranslationField.DESCRIPTION, locale, text, TranslationSource.ADMIN, true));
        } else if (event.adminClear(EventLockableField.DESCRIPTION)) {
            // 지운 관리자 설명의 번역은 더 이상 원문이 없으므로 함께 지운다
            translationRepository.deleteAllByEventIdAndField(event.getId(), TranslationField.DESCRIPTION);
            cleared = true;
        }
        return cleared;
    }

    private static boolean applySourced(Event event, EventLockableField field, Optional<?> value) {
        if (value == null) {
            return false;
        }
        if (value.isPresent()) {
            event.adminUpdate(field, value.get());
            return false;
        }
        return event.adminClear(field);
    }

    private void saveNameTranslations(Event event, LocalizedText name) {
        TranslationDrafts.Drafted drafted = translationDrafts.of(name, event.getOriginalLocale());
        drafted.texts().forEach((locale, text) ->
                upsertTranslation(event, TranslationField.NAME, locale, text, drafted.source(), drafted.reviewed()));
    }

    private void upsertTranslation(Event event, TranslationField field, SupportedLocale locale, String text,
                                   TranslationSource source, boolean reviewed) {
        translationRepository.findByEventIdAndFieldAndLocale(event.getId(), field, locale).ifPresentOrElse(
                existing -> existing.update(text, source, reviewed),
                () -> translationRepository.save(new EventTranslation(event, field, locale, text, source, reviewed)));
    }

    private Event load(Long eventId) {
        return eventRepository.findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
    }
}
