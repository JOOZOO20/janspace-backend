package com.stagelog.setlist.application;

import com.stagelog.catalog.application.CatalogQuery;
import com.stagelog.catalog.application.SongSnapshot;
import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.setlist.domain.EditQuotaPolicy;
import com.stagelog.setlist.domain.RevisionSource;
import com.stagelog.setlist.domain.SetlistPage;
import com.stagelog.setlist.domain.SetlistRevisionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팬 편집: 곡 존재 검증 -> 중복 제거·순서 1..n 재부여 -> 편집 횟수 검사(429) -> 낙관적 버전 검사(409) -> FAN 리비전.
 * 편집 뒤 "setlist.fm 에 없음" 표시는 이전 항목의 값을 이어받고 새 곡은 false 다.
 * 편집 횟수는 세고 나서(count) 리비전을 넣으므로, 같은 사용자의 요청이 동시에 오면 동시에 온 개수만큼 한도를 조금 넘을 수 있다.
 * 남용 방지용 상한이라 정확한 차단보다 단순함을 택했다 (사용자 단위 잠금을 두지 않는다).
 */
@Slf4j
@Service
public class EditSetlistUseCase {

    private final SetlistWriter writer;
    private final SetlistRevisionRepository revisions;
    private final CatalogQuery catalogQuery;
    private final EditQuotaPolicy quota;
    private final Clock clock;

    @Autowired
    public EditSetlistUseCase(SetlistWriter writer, SetlistRevisionRepository revisions, CatalogQuery catalogQuery,
                              @Value("${stagelog.setlist.edits-per-day:20}") int editsPerDay) {
        this(writer, revisions, catalogQuery, editsPerDay, Clock.systemUTC());
    }

    EditSetlistUseCase(SetlistWriter writer, SetlistRevisionRepository revisions, CatalogQuery catalogQuery, int editsPerDay, Clock clock) {
        this.writer = writer;
        this.revisions = revisions;
        this.catalogQuery = catalogQuery;
        this.quota = new EditQuotaPolicy(editsPerDay);
        this.clock = clock;
    }

    // songIds 는 요청 order 로 정렬된 상태. expectedVersion 이 null 이면 버전 검사를 건너뛴다. 새 버전을 돌려준다
    @Transactional
    public int edit(Long eventId, Long userId, List<Long> songIds, Integer expectedVersion) {
        // 1. 중복 제거 + 곡 존재 검증
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(songIds));
        Set<Long> known = catalogQuery.findSongs(unique).stream().map(SongSnapshot::id).collect(Collectors.toSet());
        if (!known.containsAll(unique)) {
            throw new NotFoundException(ErrorCode.SONG_NOT_FOUND);
        }
        // 2. 편집 횟수 (UTC 날짜)
        checkQuota(userId);
        // 3. 낙관적 잠금
        SetlistPage page = writer.pageOf(eventId);
        if (expectedVersion != null && expectedVersion != page.getVersion()) {
            log.warn("[검증 실패 - 셋리 버전 충돌] eventId={}, expected={}, actual={}", eventId, expectedVersion, page.getVersion());
            throw new ConflictException(ErrorCode.SETLIST_VERSION_CONFLICT);
        }
        // 4. 순서 1..n, 플래그는 기존 값 유지
        Map<Long, Boolean> flags = writer.currentEntries(eventId).stream()
                .collect(Collectors.toMap(SetlistEntry::songId, SetlistEntry::inSetlistFm));
        List<SetlistEntry> entries = new ArrayList<>();
        for (int i = 0; i < unique.size(); i++) {
            entries.add(new SetlistEntry(unique.get(i), i + 1, flags.getOrDefault(unique.get(i), false)));
        }
        return writer.write(page, entries, RevisionSource.FAN, userId);
    }

    // RevertSetlistUseCase 도 같은 한도를 쓴다
    void checkQuota(Long userId) {
        Instant now = clock.instant();
        long today = revisions.countByEditorUserIdAndSourceInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                userId, EditQuotaPolicy.COUNTED, EditQuotaPolicy.utcDayStart(now), EditQuotaPolicy.utcDayEnd(now));
        if (!quota.allows(today)) {
            log.warn("[검증 실패 - 셋리 편집 한도] userId={}, today={}, limit={}", userId, today, quota.limit());
            throw new ApplicationException(ErrorCode.SETLIST_EDIT_LIMIT);
        }
    }
}
