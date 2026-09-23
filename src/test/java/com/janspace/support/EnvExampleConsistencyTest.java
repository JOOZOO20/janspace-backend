package com.janspace.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * application*.yml 의 ${ENV} 자리표시자와 .env.example 의 이름이 양방향으로 일치하는지 확인한다.
 * 새 환경변수를 yml 에만 넣거나, .env.example 에 죽은 이름이 남는 것을 막는다.
 */
class EnvExampleConsistencyTest {

    // 코드가 아니라 실행 환경이 주는 이름. .env.example 에 적지 않는다
    static final Set<String> INTERNAL = Set.of("SPRING_PROFILES_ACTIVE");
    static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)");
    // 기본값이 있는 변수는 `# NAME=` 로 주석 처리돼 있어도 목록에 있는 것으로 센다
    static final Pattern ENV_LINE = Pattern.compile("^#?\\s*([A-Z0-9_]+)=");

    @Test
    void yml_placeholders_and_env_example_match_both_ways() throws IOException {
        Path root = ProjectPaths.projectRoot();
        Set<String> placeholders = new TreeSet<>();
        try (Stream<Path> files = Files.list(root.resolve("src/main/resources"))) {
            for (Path yml : files.filter(p -> p.getFileName().toString().matches("application.*\\.yml")).toList()) {
                Matcher m = PLACEHOLDER.matcher(Files.readString(yml));
                while (m.find()) {
                    placeholders.add(m.group(1));
                }
            }
        }
        placeholders.removeAll(INTERNAL);

        Set<String> listed = new TreeSet<>();
        for (String line : Files.readAllLines(root.resolve(".env.example"))) {
            Matcher m = ENV_LINE.matcher(line);
            if (m.find()) {
                listed.add(m.group(1));
            }
        }

        assertThat(placeholders).as("yml 에 있는데 .env.example 에 없는 변수").isSubsetOf(listed);
        assertThat(listed).as(".env.example 에 있는데 어떤 yml 도 쓰지 않는 변수").isSubsetOf(placeholders);
    }
}
