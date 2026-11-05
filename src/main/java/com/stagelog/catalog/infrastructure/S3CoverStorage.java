package com.stagelog.catalog.infrastructure;

import com.stagelog.catalog.application.port.CoverStoragePort;
import com.stagelog.integration.s3.S3ObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CoverStoragePort 의 S3 구현. key prefix(albums/{albumId}/)는 application 이 정한다.
 */
@Component
@RequiredArgsConstructor
public class S3CoverStorage implements CoverStoragePort {

    private final S3ObjectStorage storage;

    @Override
    public String put(String key, byte[] bytes, String contentType) {
        return storage.put(key, bytes, contentType);
    }

    @Override
    public String putVersioned(String prefix, String extension, byte[] bytes, String contentType) {
        return storage.putVersioned(prefix, extension, bytes, contentType);
    }

    @Override
    public byte[] get(String key) {
        return storage.get(key);
    }

    @Override
    public String publicUrl(String key) {
        return storage.publicUrl(key);
    }
}
