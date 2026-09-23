package com.janspace.support;

import com.janspace.bootstrap.JanspaceApplication;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 통합 테스트 공통 기반. db/schema 로 초기화한 MySQL 과 Redis 컨테이너를 JVM 당 한 번 띄우고 @ServiceConnection 으로 연결한다.
 * 매 실행이 새 DB 를 검증해야 하므로 컨테이너 재사용(withReuse)은 쓰지 않는다.
 * S3Client 는 실제 AWS 를 향하지 않도록 Mockito 목으로 대체한다 (AwsConfig 의 빈을 덮어쓴다).
 */
@MockitoBean(types = S3Client.class)
@SpringBootTest(classes = JanspaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
public abstract class IntegrationTest {

    public static final String MYSQL_IMAGE = "mysql:8.4";
    public static final String[] MYSQL_COMMAND = {
            "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci"};

    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = freshMysql(ProjectPaths.schemaDir());

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    // 주어진 초기화 SQL 디렉터리로 새 MySQL 컨테이너를 만든다 (시작은 호출자가)
    public static MySQLContainer<?> freshMysql(Path initDir) {
        return new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName("janspace")
                .withUsername("janspace")
                .withPassword("janspace")
                .withEnv("TZ", "UTC")
                .withCopyFileToContainer(MountableFile.forHostPath(initDir), "/docker-entrypoint-initdb.d")
                .withCommand(MYSQL_COMMAND);
    }

}
