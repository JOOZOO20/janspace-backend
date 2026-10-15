package com.janspace.account.infrastructure;

import com.janspace.account.application.port.AvatarStoragePort;
import com.janspace.integration.s3.S3ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * AvatarStoragePort 의 S3 구현. 저장·공개 URL 은 공용 S3ObjectStorage 를 쓰고, 공용 쪽에 삭제가 없어 삭제만 S3Client 로 직접 부른다.
 */
@Component
public class S3AvatarStorage implements AvatarStoragePort {

    private final S3ObjectStorage storage;
    private final S3Client s3Client;
    private final String bucket;

    public S3AvatarStorage(S3ObjectStorage storage, S3Client s3Client,
                           @Value("${janspace.storage.bucket:}") String bucket) {
        this.storage = storage;
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        storage.put(key, bytes, contentType);
    }

    // S3 DeleteObject 는 없는 키에도 성공한다
    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public String publicUrl(String key) {
        return storage.publicUrl(key);
    }
}
