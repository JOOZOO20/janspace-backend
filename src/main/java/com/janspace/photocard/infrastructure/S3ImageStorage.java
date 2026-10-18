package com.janspace.photocard.infrastructure;

import com.janspace.integration.s3.S3ObjectStorage;
import com.janspace.photocard.application.port.ImageStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ImageStoragePort 의 S3 구현. 키 규칙(photocards/{userId}/{uuid}.{ext}, stickers/{setId}/{uuid}.{ext})은 application 이 정한다.
 */
@Component
@RequiredArgsConstructor
public class S3ImageStorage implements ImageStoragePort {

    private final S3ObjectStorage storage;

    @Override
    public String put(String key, byte[] bytes, String contentType) {
        return storage.put(key, bytes, contentType);
    }

    @Override
    public String publicUrl(String key) {
        return storage.publicUrl(key);
    }
}
