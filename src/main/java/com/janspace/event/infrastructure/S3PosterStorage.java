package com.janspace.event.infrastructure;

import com.janspace.event.application.port.PosterStoragePort;
import com.janspace.integration.s3.S3ObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PosterStoragePort 의 S3 구현.
 */
@Component
@RequiredArgsConstructor
public class S3PosterStorage implements PosterStoragePort {

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
