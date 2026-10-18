package com.janspace.attendance.application;

import com.janspace.account.application.AccountQuery;
import com.janspace.account.application.AccountSnapshot;
import com.janspace.attendance.application.AttendanceQuery.MyStatus;
import com.janspace.attendance.application.event.AttendanceDeleted;
import com.janspace.attendance.domain.Attendance;
import com.janspace.attendance.domain.AttendanceRepository;
import com.janspace.attendance.domain.DepartureSnapshot;
import com.janspace.attendance.domain.Emotion;
import com.janspace.catalog.application.CatalogQuery;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 갈래요·갔어요·기록 생성/수정/삭제. 유저 x 행사 1행이며 소프트 삭제 행은 되살린다. 저장 후 사용자 체인 전체를 재계산한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AttendanceCommand {

    private final AttendanceRepository attendances;
    private final AccountQuery accountQuery;
    private final EventQuery eventQuery;
    private final CatalogQuery catalogQuery;
    private final AttendanceQuery attendanceQuery;
    private final DistanceRecalculation recalculation;
    private final ApplicationEventPublisher publisher;

    // 갈래요. 시점 제한 없음. 이미 기록한 행사는 그대로 둔다
    public MyStatus plan(Long userId, Long eventId) {
        requireEvent(eventId);
        DepartureSnapshot departure = profileDeparture(userId).orElseThrow(() -> new ConflictException(ErrorCode.DEPARTURE_REQUIRED));
        Attendance row = attendances.findByUserIdAndEventId(userId, eventId).orElseGet(() -> Attendance.of(userId, eventId));
        if (row.isDeleted()) {
            row.revive();
        }
        if (!row.isStar()) {
            row.plan(departure, Instant.now());
        }
        row = saveUnique(row);
        recalculation.forUser(userId);
        log.info("[갈래요] userId={}, eventId={}, attendanceId={}", userId, eventId, row.getId());
        return AttendanceQuery.status(row);
    }

    // 갈래요 취소 = 행 소프트 삭제. 기록(갔어요)이 있으면 409
    public MyStatus unplan(Long userId, Long eventId) {
        Optional<Attendance> found = attendances.findByUserIdAndEventId(userId, eventId).filter(a -> !a.isDeleted());
        if (found.isEmpty()) {
            return new MyStatus(AttendanceQuery.Status.NONE, null);
        }
        Attendance row = found.get();
        if (row.isStar()) {
            throw new ConflictException(ErrorCode.RECORD_ALREADY_EXISTS, "이미 기록한 행사는 갈래요를 취소할 수 없습니다.");
        }
        row.softDelete();
        log.info("[갈래요 취소] userId={}, eventId={}", userId, eventId);
        return new MyStatus(AttendanceQuery.Status.NONE, null);
    }

    // 갔어요·기록 생성. 1) 행사 날짜 확인 2) 감정 검증 3) 출발지 4) 연속 이동 5) 곡 6) 행 확보(신규/갈래요 승격/되살림) 7) 체인 재계산
    public Attendance create(Long userId, RecordInput input) {
        EventSnapshot event = requireEvent(input.eventId());
        if (LocalDate.now(ZoneId.of(event.timezone())).isBefore(event.eventDate())) {
            throw new BadRequestException(ErrorCode.EVENT_NOT_ENDED);
        }
        List<Emotion> emotions = parseEmotions(input.emotions(), input.retro());

        DepartureSnapshot departure = input.departure() != null
                ? new DepartureSnapshot(input.departure().name(), input.departure().countryCode(), input.departure().lat(), input.departure().lng())
                : profileDeparture(userId).orElseThrow(() -> new ConflictException(ErrorCode.DEPARTURE_REQUIRED));

        Attendance row = attendances.findByUserIdAndEventId(userId, input.eventId()).orElseGet(() -> Attendance.of(userId, input.eventId()));
        if (!row.isDeleted() && row.isStar()) {
            throw new ConflictException(ErrorCode.RECORD_ALREADY_EXISTS);
        }
        // 저장 전 상태로 판정하므로 직전 기록만 본다
        if (input.chainedFromPrevious() && !attendanceQuery.canChain(userId, event).eligible()) {
            throw new BadRequestException(ErrorCode.CHAIN_NOT_ALLOWED);
        }
        requireSong(input.songId());

        if (row.isDeleted()) {
            row.revive();
        }
        row.attend(emotions, input.songId(), input.memo(), departure, input.chainedFromPrevious(), Instant.now());
        row = saveUnique(row);
        recalculation.forUser(userId);
        log.info("[기록 생성] userId={}, eventId={}, attendanceId={}, retro={}, chained={}", userId, input.eventId(), row.getId(),
                row.isRetro(), row.isContinuedFromPrevious());
        return row;
    }

    // PATCH. Optional 자체가 null(필드 없음)이면 변경 없음, 비어 있으면(값 null) 지운다. 감정은 지울 수 없다(1~3개)
    public Attendance update(Long userId, Long attendanceId, Optional<List<String>> emotions, Optional<Long> songId,
                             Optional<String> memo) {
        Attendance row = own(userId, attendanceId);
        if (emotions != null) {
            row.record(parseEmotions(emotions.orElse(List.of()), false));
        }
        if (songId != null) {
            requireSong(songId.orElse(null));
            row.changeSong(songId.orElse(null));
        }
        if (memo != null) {
            row.changeMemo(memo.orElse(null));
        }
        log.info("[기록 수정] userId={}, attendanceId={}, brightness={}", userId, attendanceId, row.brightness());
        return row;
    }

    public void delete(Long userId, Long attendanceId) {
        Attendance row = own(userId, attendanceId);
        row.softDelete();
        recalculation.forUser(userId);
        publisher.publishEvent(new AttendanceDeleted(row.getId(), userId));
        log.info("[기록 삭제] userId={}, attendanceId={}", userId, attendanceId);
    }

    // 탈퇴: 사용자의 모든 행 소프트 삭제 (UserWithdrawn 리스너가 부른다)
    public void withdraw(Long userId) {
        List<Attendance> rows = attendances.findAllByUserIdAndDeletedAtIsNull(userId);
        rows.forEach(Attendance::softDelete);
        log.info("[탈퇴 기록 정리] userId={}, rows={}", userId, rows.size());
    }

    // 같은 (user, event) 를 동시에 처음 만들면 둘 다 "행 없음" 을 보고 INSERT 한다. 늦은 쪽은 uk_attendances_user_event 가 막으므로 409
    private Attendance saveUnique(Attendance row) {
        try {
            return attendances.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            log.warn("[검증 실패 - 동시 생성] userId={}, eventId={}", row.getUserId(), row.getEventId());
            throw new ConflictException(ErrorCode.RECORD_ALREADY_EXISTS);
        }
    }

    private Attendance own(Long userId, Long attendanceId) {
        return attendances.findByIdAndDeletedAtIsNull(attendanceId)
                .filter(a -> a.getUserId().equals(userId) && a.isStar())
                .orElseThrow(() -> new NotFoundException(ErrorCode.RECORD_NOT_FOUND));
    }

    private EventSnapshot requireEvent(Long eventId) {
        return eventQuery.find(eventId).orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
    }

    private void requireSong(Long songId) {
        if (songId != null && catalogQuery.findSong(songId).isEmpty()) {
            throw new NotFoundException(ErrorCode.SONG_NOT_FOUND);
        }
    }

    private Optional<DepartureSnapshot> profileDeparture(Long userId) {
        return accountQuery.find(userId).map(AccountSnapshot::departure)
                .map(d -> new DepartureSnapshot(d.name(), d.countryCode(), d.lat(), d.lng()));
    }

    // 1~3개, 중복·미지 코드 없음. 소급(retro) 이면 0개 허용
    static List<Emotion> parseEmotions(List<String> codes, boolean retro) {
        List<String> raw = codes == null ? List.of() : codes;
        if (raw.isEmpty()) {
            if (retro) {
                return List.of();
            }
            throw new BadRequestException(ErrorCode.INVALID_EMOTIONS);
        }
        if (raw.size() > Attendance.MAX_EMOTIONS) {
            throw new BadRequestException(ErrorCode.INVALID_EMOTIONS);
        }
        List<Emotion> parsed = raw.stream()
                .map(c -> Emotion.parse(c).orElseThrow(() -> new BadRequestException(ErrorCode.INVALID_EMOTIONS, "알 수 없는 감정 코드입니다: " + c)))
                .toList();
        if (new HashSet<>(parsed).size() != parsed.size()) {
            throw new BadRequestException(ErrorCode.INVALID_EMOTIONS, "감정이 중복됐습니다.");
        }
        return parsed;
    }
}
