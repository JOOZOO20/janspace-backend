package com.stagelog.integration.s3;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 객체 저장. 여러 기능(포스터·커버·포토카드)이 공유한다. 자격 증명은 SDK 기본 체인(bootstrap.AwsConfig).
 */
@Component
public class S3ObjectStorage {

    // 내용이 바뀌면 key 가 바뀌므로 1년(최대 권장값) 캐시하고 재검증하지 않게 한다
    public static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";
    static final int HASH_LENGTH = 16;

    private final S3Client s3Client;
    private final String bucket;
    private final String cdnBaseUrl;

    public S3ObjectStorage(S3Client s3Client,
                           @Value("${stagelog.storage.bucket:}") String bucket,
                           @Value("${stagelog.storage.cdn-base-url:}") String cdnBaseUrl) {
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

    /**
     * 내용 주소 key(prefix + sha256(bytes) 앞 16자 + "." + extension)로 저장하고 key 를 돌려준다.
     * 같은 key 가 이미 있으면(HEAD 200) 같은 내용이므로 PUT 을 건너뛴다. 새 객체에는 Cache-Control immutable 을 붙인다.
     */
    public String putVersioned(String prefix, String extension, byte[] bytes, String contentType) {
        String key = prefix + sha256Hex(bytes).substring(0, HASH_LENGTH) + "." + extension;
        putImmutable(key, bytes, contentType);
        return key;
    }

    // 내용 hash 가 들어 있는 key 로 저장한다. 같은 key 가 이미 있으면(HEAD 200) 같은 내용이므로 PUT 을 건너뛴다. 새 객체는 Cache-Control immutable
    public void putImmutable(String key, byte[] bytes, String contentType) {
        if (exists(key)) {
            return;
        }
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                        .cacheControl(IMMUTABLE_CACHE_CONTROL).contentLength((long) bytes.length).build(),
                RequestBody.fromBytes(bytes));
    }

    // HEAD 404 = 없음. 버킷 ListBucket 권한이 없으면 S3 는 없는 key 에 404 대신 403 을 주므로 예외로 올린다
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    // 없는 key 면 NoSuchKeyException (S3Exception)
    public byte[] get(String key) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    // S3 DeleteObject 는 없는 키에도 성공한다
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    public String publicUrl(String key) {
        String base = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
        return base + "/" + key;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
