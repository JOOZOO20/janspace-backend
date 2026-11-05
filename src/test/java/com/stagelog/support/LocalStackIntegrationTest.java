package com.stagelog.support;

import com.stagelog.bootstrap.StagelogApplication;
import com.stagelog.event.application.port.TimezonePort;
import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 실제 S3·SQS 동작이 필요한 통합 테스트 기반. LocalStack(S3, SQS) 컨테이너를 JVM 당 한 번 띄우고, AwsConfig 의 S3Client 가
 * stagelog.aws.endpoint 로 LocalStack 을 부르게 한다. MySQL·Redis 는 IntegrationTest 와 같은 컨테이너를 쓴다.
 * S3Client 는 실제 빈을 Mockito spy 로 감싸므로 호출 수(putObject 등)를 셀 수 있다. 공개 URL 기본 주소는 버킷 주소(경로 방식)라
 * publicUrl 로 받은 URL 을 HTTP 로 바로 GET 할 수 있다. SQS 클라이언트는 LOCALSTACK 의 엔드포인트·자격 증명으로 만든다.
 * 무거운 timeshape 초기화를 피하려고 TimezonePort 는 목으로 둔다.
 */
@SpringBootTest(classes = StagelogApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
public abstract class LocalStackIntegrationTest {

    public static final String BUCKET = "stagelog-test";

    // 4.x 최신 이미지는 인증 토큰을 요구하므로 토큰 없이 도는 마지막 커뮤니티 버전에 고정한다
    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1")).withServices("s3", "sqs");

    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = IntegrationTest.MYSQL;

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = IntegrationTest.REDIS;

    static {
        LOCALSTACK.start();
        // 앱의 S3Client 는 기본 자격 증명 체인을 쓴다. 체인의 첫 단계(시스템 속성)에 LocalStack 자격 증명을 둔다
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
        try (S3Client admin = adminS3()) {
            admin.createBucket(b -> b.bucket(BUCKET));
        }
    }

    @MockitoSpyBean
    protected S3Client s3Client;

    @MockitoBean
    protected TimezonePort timezonePort;

    @DynamicPropertySource
    static void localStackProperties(DynamicPropertyRegistry registry) {
        registry.add("stagelog.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("stagelog.storage.region", LOCALSTACK::getRegion);
        registry.add("stagelog.storage.bucket", () -> BUCKET);
        registry.add("stagelog.storage.cdn-base-url", LocalStackIntegrationTest::bucketUrl);
    }

    // 버킷의 경로 방식 주소. 공개 URL = bucketUrl() + "/" + key
    protected static String bucketUrl() {
        return LOCALSTACK.getEndpoint() + "/" + BUCKET;
    }

    // 앱 빈과 별개인 S3 클라이언트 (버킷 준비·검증용). 호출자가 닫는다
    protected static S3Client adminS3() {
        return S3Client.builder()
                .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .forcePathStyle(true)
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }
}
