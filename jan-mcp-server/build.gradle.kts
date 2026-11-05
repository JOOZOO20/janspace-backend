plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.stagelog"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        // Spring AI 2.0.x 는 Spring Boot 4.0.x·4.1.x 를 지원한다 (docs.spring.io/spring-ai/reference/getting-started.html)
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
    }
}

dependencies {
    // MCP Server Starter (Streamable HTTP, Spring MVC). 프로토콜 구현은 MCP Java SDK(io.modelcontextprotocol.sdk:mcp-core)
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
