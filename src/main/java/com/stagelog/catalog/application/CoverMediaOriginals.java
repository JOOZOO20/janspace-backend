package com.stagelog.catalog.application;

import com.stagelog.catalog.domain.AlbumRepository;
import com.stagelog.media.application.MediaOriginals;
import com.stagelog.media.application.MediaOwnerType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미디어 백필에 앨범 커버 원본을 내준다 (media 의 역방향 계약 구현).
 */
@Component
@RequiredArgsConstructor
public class CoverMediaOriginals implements MediaOriginals {

    private final AlbumRepository albums;

    @Override
    public boolean supports(MediaOwnerType ownerType) {
        return ownerType == MediaOwnerType.ALBUM;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Entry> after(MediaOwnerType ownerType, long afterOwnerId, int limit) {
        return albums.findCoverKeysAfter(afterOwnerId, PageRequest.of(0, limit)).stream()
                .map(row -> new Entry((Long) row[0], (String) row[1])).toList();
    }
}
