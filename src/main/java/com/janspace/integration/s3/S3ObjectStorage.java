package com.janspace.integration.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 객체 저장. 여러 기능(포스터·커버·포토카드)이 공유한다. 자격 증명은 SDK 기본 체인(bootstrap.AwsConfig).
 */
@Component
public class S3ObjectStorage {

    private final S3Client s3Client;
    private final String bucket;
    private final String cdnBaseUrl;

    public S3ObjectStorage(S3Client s3Client,
                           @Value("${janspace.storage.bucket:}") String bucket,
                           @Value("${janspace.storage.cdn-base-url:}") String cdnBaseUrl) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.cdnBaseUrl = cdnBaseUrl;
    }

    // 저장 후 키를 돌려준다
    public String put(String key, byte[] bytes, String contentType) {
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                        .contentLength((long) bytes.length).build(),
                RequestBody.fromBytes(bytes));
        return key;
    }

    // S3 DeleteObject 는 없는 키에도 성공한다
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    public String publicUrl(String key) {
        String base = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
        return base + "/" + key;
    }
}
