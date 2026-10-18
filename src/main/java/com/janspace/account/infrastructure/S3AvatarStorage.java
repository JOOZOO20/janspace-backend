package com.janspace.account.infrastructure;

import com.janspace.account.application.port.AvatarStoragePort;
import com.janspace.integration.s3.S3ObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AvatarStoragePort 의 S3 구현. 저장·삭제·공개 URL 모두 공용 S3ObjectStorage 에 맡긴다.
 */
@Component
@RequiredArgsConstructor
public class S3AvatarStorage implements AvatarStoragePort {

    private final S3ObjectStorage storage;

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        storage.put(key, bytes, contentType);
    }

    @Override
    public void delete(String key) {
        storage.delete(key);
    }

    @Override
    public String publicUrl(String key) {
        return storage.publicUrl(key);
    }
}
