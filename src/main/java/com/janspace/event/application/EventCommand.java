package com.janspace.event.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.common.locale.LocalizedText;
import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.application.VenueCommand.VenueInput;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.application.event.EventVenueChanged;
import com.janspace.event.application.port.PosterStoragePort;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.EventRepository;
import com.janspace.event.domain.EventStatus;
import com.janspace.event.domain.EventTranslation;
import com.janspace.event.domain.EventTranslationRepository;
import com.janspace.event.domain.EventType;
import com.janspace.event.domain.TranslationField;
import com.janspace.event.domain.TranslationSource;
import com.janspace.event.domain.Venue;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 행사 생성·수정. 관리자가 준 필드는 전부 adminUpdate 로 들어가 잠기고, 잠금 해제는 곧바로 출처 접기를 다시 실행한다.
 * 일정 필드가 바뀌면 EventScheduleChanged, 공연장이 바뀌면 EventVenueChanged 를 발행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventCommand {

    // 관리자 입력 원어는 한국어로 본다 (기획: 한국어 완성 -> 다국어). 다른 원어가 필요하면 번역 행으로 표현한다
    static final SupportedLocale ADMIN_LOCALE = SupportedLocale.KO;
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final EventRepository eventRepository;
    private final EventTranslationRepository translationRepository;
    private final VenueCommand venueCommand;
    private final ApplySourcesUseCase applySourcesUseCase;
    private final TranslationDrafts translationDrafts;
    private final PosterStoragePort posterStoragePort;
    private final ApplicationEventPublisher publisher;

    /** 관리자 입력. 생성은 name·kind·date 필수, 수정은 null 필드를 "변경 없음"으로 본다 (venue null 도 변경 없음). */
    public record EventInput(LocalizedText name, EventType kind, LocalDate date, LocalTime startTime, LocalTime endTime,
                             VenueInput venue, String season, EventStatus status) {
    }

    @Transactional
    public Long create(EventInput input) {
        if (input.name() == null || input.kind() == null || input.date() == null) {
            throw new IllegalArgumentException("name, kind, date 는 필수입니다");
        }
        // 1. 생성 후 관리자 값을 전부 잠금과 함께 적용
        Event event = Event.create(input.name().original(), ADMIN_LOCALE, input.kind(), input.date());
        event.adminUpdate(EventLockableField.NAME, input.name().original());
        event.adminUpdate(EventLockableField.TYPE, input.kind());
        event.adminUpdate(EventLockableField.EVENT_DATE, input.date());
        applyOptional(event, input);
        eventRepository.save(event);
        // 2. 이름 번역: 관리자 번역 또는 기계 초안
        saveNameTranslations(event, input.name());
        // 3. 새 행사의 일정은 알림 예약 대상
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        if (event.getVenue() != null) {
            publisher.publishEvent(new EventVenueChanged(event.getId()));
        }
        log.info("[행사 생성] eventId={}, date={}, venueId={}", event.getId(), event.getEventDate(), event.venueId());
        return event.getId();
    }

    @Transactional
    public void update(Long eventId, EventInput input) {
        Event event = load(eventId);
        boolean scheduleChanged = false;
        boolean venueChanged = false;
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
        if (scheduleChanged) {
            publisher.publishEvent(new EventScheduleChanged(eventId));
        }
        if (venueChanged) {
            publisher.publishEvent(new EventVenueChanged(eventId));
        }
        log.info("[행사 관리자 수정] eventId={}, locked={}", eventId, event.getLockedFields());
    }

    // 잠금 해제 = 출처 값 따르기. 해제 즉시 접기를 다시 실행한다
    @Transactional
    public void unlock(Long eventId, EventLockableField field) {
        load(eventId).unlock(field);
        applySourcesUseCase.refold(eventId);
        log.info("[행사 잠금 해제] eventId={}, field={}", eventId, field);
    }

    @Transactional
    public void upsertTranslation(Long eventId, TranslationField field, SupportedLocale locale, String text, boolean reviewed) {
        upsertTranslation(load(eventId), field, locale, text, TranslationSource.ADMIN, reviewed);
    }

    // 포스터 업로드 -> S3 -> POSTER 잠금. 공개 URL 을 돌려준다
    @Transactional
    public String uploadPoster(Long eventId, byte[] bytes, String contentType) {
        String extension = contentType == null ? null : IMAGE_EXTENSIONS.get(contentType.toLowerCase());
        if (extension == null || bytes == null || bytes.length == 0) {
            throw new BadRequestException(ErrorCode.POSTER_INVALID);
        }
        Event event = load(eventId);
        String key = "events/" + eventId + "/poster." + extension;
        // 외부 호출 (S3)
        posterStoragePort.put(key, bytes, contentType);
        event.adminUpdate(EventLockableField.POSTER, key);
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
