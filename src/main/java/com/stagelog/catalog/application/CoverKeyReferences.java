package com.stagelog.catalog.application;

import com.stagelog.catalog.domain.AlbumRepository;
import com.stagelog.ops.application.ObjectKeyReferences;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 앨범커버 key 참조 확인 (S3 이전 key 삭제 배치용). albums.cover_key 가 그 key 면 참조 중이다.
 */
@Component
@RequiredArgsConstructor
public class CoverKeyReferences implements ObjectKeyReferences {

    private final AlbumRepository albums;

    @Override
    public boolean isReferenced(String key) {
        return albums.existsByCoverKey(key);
    }
}
