package com.stagelog.integration.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3ObjectStorageTest {

    S3Client s3Client = mock(S3Client.class);
    S3ObjectStorage storage = new S3ObjectStorage(s3Client, "stagelog-images", "https://cdn.example.com/");

    @Test
    @DisplayName("put 은 버킷·키·콘텐츠 타입·길이를 담은 PutObjectRequest 를 보낸다")
    void put_builds_request() {
        byte[] bytes = {1, 2, 3};

        String key = storage.put("albums/3.png", bytes, "image/png");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest request = captor.getValue();
        assertThat(key).isEqualTo("albums/3.png");
        assertThat(request.bucket()).isEqualTo("stagelog-images");
        assertThat(request.key()).isEqualTo("albums/3.png");
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(request.contentLength()).isEqualTo(3L);
    }

    @Test
    @DisplayName("publicUrl 은 CDN 기본 주소의 끝 슬래시를 정리하고 키를 붙인다")
    void public_url() {
        assertThat(storage.publicUrl("albums/3.png")).isEqualTo("https://cdn.example.com/albums/3.png");
    }

    @Test
    @DisplayName("putVersioned 는 prefix + sha256 앞 16자 + 확장자 key 로, HEAD 404 면 Cache-Control immutable 을 붙여 PUT 한다")
    void put_versioned_uploads_new_content() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        String key = storage.putVersioned("albums/3/", "png", new byte[] {1, 2, 3}, "image/png");

        // sha256(01 02 03) = 039058c6f2c0cb49...
        assertThat(key).isEqualTo("albums/3/039058c6f2c0cb49.png");
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().key()).isEqualTo(key);
        assertThat(captor.getValue().cacheControl()).isEqualTo("public, max-age=31536000, immutable");
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("putVersioned 는 같은 key 가 이미 있으면(HEAD 200) PUT 하지 않는다")
    void put_versioned_skips_existing_content() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        String key = storage.putVersioned("albums/3/", "png", new byte[] {1, 2, 3}, "image/png");

        assertThat(key).isEqualTo("albums/3/039058c6f2c0cb49.png");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("HEAD 가 404 가 아닌 오류(ListBucket 권한 없음 403 등)면 덮어쓰지 않고 예외를 올린다")
    void exists_rethrows_non_404() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403).build());

        assertThatThrownBy(() -> storage.putVersioned("albums/3/", "png", new byte[] {1, 2, 3}, "image/png"))
                .isInstanceOf(S3Exception.class);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
