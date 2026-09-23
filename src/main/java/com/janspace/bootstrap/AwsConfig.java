package com.janspace.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS SDK 클라이언트. 자격 증명은 기본 체인(EC2 IAM 역할, 로컬 ~/.aws 또는 AWS_* 환경변수)만 쓰며 액세스 키를 직접 읽지 않는다.
 */
@Configuration
public class AwsConfig {

    @Bean
    public S3Client s3Client(@Value("${janspace.storage.region:ap-northeast-2}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }
}
