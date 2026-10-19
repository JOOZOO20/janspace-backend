package com.stagelog.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 프로젝트 루트 기준 경로. Gradle 은 루트에서 실행하지만 IDE 는 아닐 수 있어 settings.gradle.kts 를 기준으로 위로 찾는다.
 * 컨테이너를 띄우지 않는 단위 테스트도 쓰므로 IntegrationTest 와 분리한다.
 */
public final class ProjectPaths {

    private ProjectPaths() {
    }

    public static Path projectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("settings.gradle.kts 를 찾을 수 없어 프로젝트 루트를 정할 수 없습니다");
    }

    public static Path schemaDir() {
        return projectRoot().resolve("db/schema");
    }
}
