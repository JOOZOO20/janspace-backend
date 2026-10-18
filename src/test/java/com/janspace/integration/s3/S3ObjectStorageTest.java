package com.janspace.integration.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class S3ObjectStorageTest {

    S3Client s3Client = mock(S3Client.class);
    S3ObjectStorage storage = new S3ObjectStorage(s3Client, "janspace-images", "https://cdn.example.com/");

    @Test
    @DisplayName("put 은 버킷·키·콘텐츠 타입·길이를 담은 PutObjectRequest 를 보낸다")
    void put_builds_request() {
        byte[] bytes = {1, 2, 3};

        String key = storage.put("albums/3.png", bytes, "image/png");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest request = captor.getValue();
        assertThat(key).isEqualTo("albums/3.png");
        assertThat(request.bucket()).isEqualTo("janspace-images");
        assertThat(request.key()).isEqualTo("albums/3.png");
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(request.contentLength()).isEqualTo(3L);
    }

    @Test
    @DisplayName("publicUrl 은 CDN 기본 주소의 끝 슬래시를 정리하고 키를 붙인다")
    void public_url() {
        assertThat(storage.publicUrl("albums/3.png")).isEqualTo("https://cdn.example.com/albums/3.png");
    }
}
