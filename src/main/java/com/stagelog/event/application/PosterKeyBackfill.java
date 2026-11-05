package com.stagelog.event.application;

import com.stagelog.event.application.port.PosterStoragePort;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.ops.application.StorageCleanup;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 포스터 고정 key(events/{id}/poster.{ext}) -> 버전 key 백필 (1회성, 다시 실행해도 남은 고정 key 만 처리).
 * 고정 key 는 events.poster_key 와 출처 스냅샷(event_sources.raw 의 posterUrl, KOPIS 재저장)에 있다. 둘 다 바꿔야
 * 잠금 해제·재접기가 옛 key 로 되돌리지 않는다. S3 복사는 트랜잭션 밖에서, DB 갱신은 행사마다 짧은 트랜잭션으로 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PosterKeyBackfill {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg", "png", "image/png", "gif", "image/gif", "webp", "image/webp");

    private final EventRepository eventRepository;
    private final EventSourceRepository sourceRepository;
    private final SourceSnapshotCodec codec;
    private final PosterStoragePort posterStorage;
    private final StorageCleanup storageCleanup;
    private final TransactionTemplate transactions;

    /** migrated = 버전 key 로 옮기고 삭제 예약한 고정 key 수, failed = 원본 객체를 못 읽는 등으로 건너뛴 key 수. */
    public record Result(int migrated, int failed) {
    }

    public Result run() {
        // 1. 고정 key 수집 (행사 컬럼 + 출처 스냅샷)
        TreeSet<String> fixedKeys = new TreeSet<>();
        eventRepository.findAllByPosterKeyLike("events/%/poster.%").stream()
                .map(Event::getPosterKey).filter(PosterKeys::isFixed).forEach(fixedKeys::add);
        for (EventSource source : sourceRepository.findAll()) {
            String posterUrl = codec.read(source.getRaw()).posterUrl();
            if (PosterKeys.isFixed(posterUrl)) {
                fixedKeys.add(posterUrl);
            }
        }

        // 2. 내용 hash key 로 복사. 같은 내용 key 가 이미 있으면 PUT 하지 않는다
        Map<Long, Map<String, String>> byEvent = new LinkedHashMap<>();
        int failed = 0;
        for (String oldKey : fixedKeys) {
            Long eventId = PosterKeys.eventIdOf(oldKey).orElseThrow();
            String ext = PosterKeys.extensionOf(oldKey);
            try {
                // 외부 호출: S3 GetObject -> HEAD/PutObject
                String newKey = posterStorage.putVersioned(PosterKeys.versionedPrefix(eventId), ext, posterStorage.get(oldKey),
                        CONTENT_TYPES.getOrDefault(ext, "image/jpeg"));
                byEvent.computeIfAbsent(eventId, id -> new HashMap<>()).put(oldKey, newKey);
            } catch (RuntimeException e) {
                log.warn("[포스터 key 백필 실패] eventId={}, key={}", eventId, oldKey, e);
                failed++;
            }
        }

        // 3. 행사마다 poster_key·출처 스냅샷을 새 key 로 바꾸고 옛 key 를 7일 뒤 삭제 예약(MIGRATED)
        int migrated = 0;
        for (Map.Entry<Long, Map<String, String>> entry : byEvent.entrySet()) {
            Integer count = transactions.execute(status -> migrate(entry.getKey(), entry.getValue()));
            migrated += count == null ? 0 : count;
        }
        log.info("[포스터 key 백필] fixedKeys={}, migrated={}, failed={}", fixedKeys.size(), migrated, failed);
        return new Result(migrated, failed);
    }

    private int migrate(Long eventId, Map<String, String> moves) {
        eventRepository.findById(eventId).ifPresent(event -> {
            String current = event.getPosterKey();
            if (moves.containsKey(current)) {
                event.migratePosterKey(current, moves.get(current));
            }
        });
        for (EventSource source : sourceRepository.findAllByEventId(eventId)) {
            SourceSnapshot snapshot = codec.read(source.getRaw());
            String newKey = moves.get(snapshot.posterUrl());
            if (newKey != null) {
                // 제공자에게서 다시 받은 것이 아니므로 fetchedAt 은 그대로 둔다
                source.refresh(source.getExternalId(), source.getOccurrenceKey(), codec.write(snapshot.withPosterUrl(newKey)),
                        source.getFetchedAt());
            }
        }
        moves.keySet().forEach(storageCleanup::scheduleMigrated);
        return moves.size();
    }
}
