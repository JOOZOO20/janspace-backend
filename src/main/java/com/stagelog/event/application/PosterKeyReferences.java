package com.stagelog.event.application;

import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.ops.application.ObjectKeyReferences;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 포스터 key 참조 확인 (S3 이전 key 삭제 배치용). events.poster_key 가 그 key 이거나, 그 행사 출처 스냅샷(event_sources.raw)의
 * posterUrl 이 그 key 면 참조 중이다. 관리자 포스터로 잠긴 동안 KOPIS 포스터 key 는 raw 에만 남고, 잠금을 풀면 접기가 그 key 로 돌아간다.
 */
@Component
@RequiredArgsConstructor
public class PosterKeyReferences implements ObjectKeyReferences {

    private final EventRepository eventRepository;
    private final EventSourceRepository sourceRepository;
    private final SourceSnapshotCodec codec;

    @Override
    public boolean isReferenced(String key) {
        if (eventRepository.existsByPosterKey(key)) {
            return true;
        }
        // raw 는 JSON 이라 내용으로 조회하지 않고, key 에 든 행사 ID 의 출처만 읽어 비교한다
        return PosterKeys.eventIdOf(key)
                .map(eventId -> sourceRepository.findAllByEventId(eventId).stream()
                        .anyMatch(source -> key.equals(codec.read(source.getRaw()).posterUrl())))
                .orElse(false);
    }
}
