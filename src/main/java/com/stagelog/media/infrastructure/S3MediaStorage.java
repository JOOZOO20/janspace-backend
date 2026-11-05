package com.stagelog.media.infrastructure;

import com.stagelog.integration.s3.S3ObjectStorage;
import com.stagelog.media.application.port.MediaStoragePort;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * MediaStoragePort 의 S3 구현.
 */
@Component
@RequiredArgsConstructor
public class S3MediaStorage implements MediaStoragePort {

    private final S3ObjectStorage storage;

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(storage.get(key));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void putImmutable(String key, byte[] bytes, String contentType) {
        storage.putImmutable(key, bytes, contentType);
    }

    @Override
    public String publicUrl(String key) {
        return storage.publicUrl(key);
    }
}
