plugins {
    java
    id("org.springframework.boot") version "4.0.8"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.janspace"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // OAuth 인가 코드 교환은 RestClient 로 직접 구현한다 (Spring Security oauth2-client 미사용). JWT 서명은 Nimbus
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // KOPIS 는 XML 응답 (Jackson 3 XML 모듈, 버전은 Boot BOM 관리)
    implementation("tools.jackson.dataformat:jackson-dataformat-xml")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    // S3 포스터 저장 (자격 증명은 SDK 기본 체인)
    implementation(platform("software.amazon.awssdk:bom:2.46.7"))
    implementation("software.amazon.awssdk:s3")
    // 좌표 -> IANA 시간대 (오프라인 데이터, 24MB)
    implementation("net.iakovlev:timeshape:2025b.26")
    // catalog: 곡 마스터 엑셀 import
    implementation("org.apache.poi:poi-ooxml:5.5.1")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 단위 테스트: integration 태그 제외
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    // timeshape 전 세계 데이터 초기화 (TimeshapeTimezoneAdapterTest)
    maxHeapSize = "2g"
}

// 통합 테스트: integration 태그만 (Docker + Testcontainers 필요)
val integrationTest by tasks.registering(Test::class) {
    description = "Testcontainers 기반 통합 테스트 (@Tag(\"integration\"))"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}
