package com.janspace.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 스키마 파일 정적 검사(C). Docker 없이 파일만 읽는다: IF NOT EXISTS 금지, 이력 INSERT 금지, changes 이름 형식, 꼬리 주석 블록.
 */
class SchemaStaticTest {

    static final Pattern RELEASE_DIR = Pattern.compile("^v\\d+\\.\\d+\\.\\d+$");
    static final Pattern CHANGE_FILE = Pattern.compile("^\\d{8}_\\d{2}_[a-z0-9_]+\\.sql$");
    static final Pattern HISTORY_INSERT = Pattern.compile("insert\\s+into\\s+`?schema_history`?", Pattern.CASE_INSENSITIVE);

    final Path root = ProjectPaths.projectRoot();

    @Test
    @DisplayName("schema·changes 의 모든 SQL 에 IF NOT EXISTS 가 없다")
    void no_if_not_exists() throws IOException {
        for (Path file : sqlFiles(root.resolve("db/schema"), root.resolve("db/changes"))) {
            String body = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            assertThat(body).as("%s 에 IF NOT EXISTS 금지", file).doesNotContain("if not exists");
        }
    }

    @Test
    @DisplayName("schema_history INSERT 는 9999_record_fresh.sql 에만 있다")
    void history_insert_only_in_record_fresh() throws IOException {
        for (Path file : sqlFiles(root.resolve("db/schema"), root.resolve("db/changes"))) {
            if (file.getFileName().toString().equals("9999_record_fresh.sql")) {
                continue;
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(HISTORY_INSERT.matcher(body).find()).as("%s 에 schema_history INSERT 금지", file).isFalse();
        }
    }

    @Test
    @DisplayName("changes 폴더·파일 이름 형식과 꼬리 검증·되돌림 주석")
    void change_files_follow_naming_and_have_footer() throws IOException {
        Path changes = root.resolve("db/changes");
        try (Stream<Path> dirs = Files.list(changes)) {
            for (Path release : dirs.filter(Files::isDirectory).toList()) {
                assertThat(release.getFileName().toString()).as("릴리스 폴더 이름").matches(RELEASE_DIR);
                for (Path file : sqlFiles(release)) {
                    assertThat(file.getFileName().toString()).as("변경 파일 이름").matches(CHANGE_FILE);
                    String body = Files.readString(file, StandardCharsets.UTF_8);
                    int tail = Math.max(0, body.lastIndexOf(';'));
                    String footer = body.substring(tail);
                    assertThat(footer).as("%s 꼬리에 검증 주석", file).contains("-- 검증:");
                    assertThat(footer).as("%s 꼬리에 되돌림 주석", file).contains("-- 되돌림:");
                }
            }
        }
    }

    static List<Path> sqlFiles(Path... dirs) throws IOException {
        List<Path> out = new java.util.ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".sql")).sorted().forEach(out::add);
            }
        }
        return out;
    }
}
