package com.stagelog.event.application;

import com.stagelog.event.domain.EventRepository;
import com.stagelog.media.application.MediaOriginals;
import com.stagelog.media.application.MediaOwnerType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미디어 백필에 행사 포스터 원본을 내준다 (media 의 역방향 계약 구현).
 */
@Component
@RequiredArgsConstructor
public class PosterMediaOriginals implements MediaOriginals {

    private final EventRepository events;

    @Override
    public boolean supports(MediaOwnerType ownerType) {
        return ownerType == MediaOwnerType.EVENT_POSTER;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Entry> after(MediaOwnerType ownerType, long afterOwnerId, int limit) {
        return events.findPosterKeysAfter(afterOwnerId, PageRequest.of(0, limit)).stream()
                .map(row -> new Entry((Long) row[0], (String) row[1])).toList();
    }
}
