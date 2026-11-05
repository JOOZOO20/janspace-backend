package com.stagelog.bootstrap;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * AWS SDK 클라이언트. 자격 증명은 기본 체인(EC2 IAM 역할, 로컬 ~/.aws 또는 AWS_* 환경변수)만 쓰며 액세스 키를 직접 읽지 않는다.
 * stagelog.aws.endpoint 가 비어 있으면 실제 AWS, 값이 있으면 그 주소(LocalStack 등 AWS 호환 엔드포인트)를 부른다.
 */
@Configuration
public class AwsConfig {

    @Bean
    public S3Client s3Client(@Value("${stagelog.storage.region:ap-northeast-2}") String region,
                             @Value("${stagelog.aws.endpoint:}") String endpoint) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            // 호환 엔드포인트는 버킷 이름을 호스트로 풀 수 없으므로 경로 방식(endpoint/bucket/key)으로 부른다
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    // Queue(JAN-04 수집·후처리 분리). 지역·엔드포인트는 S3 와 같은 설정을 쓴다
    @Bean
    public SqsClient sqsClient(@Value("${stagelog.storage.region:ap-northeast-2}") String region,
                               @Value("${stagelog.aws.endpoint:}") String endpoint) {
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
