package com.janspace.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

/**
 * 스키마 검증 (A) 새 DB: db/schema 로 만든 DB 에서 validate 부팅 + fresh 이력 행.
 * (B) 업그레이드: 이전 baseline 각각에 changes 를 적용한 결과가 (A) 의 information_schema 스냅샷과 같다.
 */
@Slf4j
class SchemaIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("(A) validate 부팅 성공 + schema_history 에 fresh@dev")
    void fresh_schema_boots_and_records_history() throws SQLException {
        try (Connection conn = connect(MYSQL);
             PreparedStatement ps = conn.prepareStatement("SELECT filename FROM schema_history");
             ResultSet rs = ps.executeQuery()) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
            assertThat(rows).contains("fresh@dev");
        }
    }

    @Test
    @DisplayName("(B) 이전 baseline + changes == 최신 schema")
    void upgrade_from_each_older_baseline_matches_fresh_schema(@TempDir Path tempDir) throws Exception {
        Path baselineDir = ProjectPaths.projectRoot().resolve("db/baseline");
        Path changesDir = ProjectPaths.projectRoot().resolve("db/changes");

        // 1. baseline 버전과 changes 릴리스 폴더 수집
        List<Semver> baselines = list(baselineDir, p -> p.toString().endsWith(".sql"))
                .map(p -> Semver.parse(stripExt(p.getFileName().toString()))).sorted().toList();
        List<Path> releaseDirs = list(changesDir, Files::isDirectory).sorted().toList();
        boolean hasChangeFiles = false;
        for (Path release : releaseDirs) {
            hasChangeFiles |= list(release, p -> p.toString().endsWith(".sql")).findAny().isPresent();
        }
        if (baselines.isEmpty() && !hasChangeFiles) {
            Assumptions.assumeTrue(false, "업그레이드 검증 대상 없음: baseline·changes 비어 있음");
        }
        Semver newest = releaseDirs.stream().map(p -> Semver.parse(p.getFileName().toString()))
                .max(Comparator.naturalOrder()).orElse(null);
        List<Semver> older = newest == null ? List.of()
                : baselines.stream().filter(b -> b.compareTo(newest) < 0).toList();
        if (hasChangeFiles && older.isEmpty()) {
            fail("db/changes 에 변경 파일이 있지만 적용해 볼 이전 baseline 이 db/baseline 에 없습니다 (최신 릴리스=" + newest + ")");
        }
        if (older.isEmpty()) {
            Assumptions.assumeTrue(false, "업그레이드 검증 대상 없음: 최신 릴리스보다 오래된 baseline 없음");
        }

        // 2. 기준 스냅샷 = db/schema 로 만든 컨테이너
        List<String> expected;
        try (Connection conn = connect(MYSQL)) {
            expected = snapshot(conn);
        }

        // 3. baseline 마다 새 컨테이너 -> 이후 릴리스 changes 순서 적용 -> 스냅샷 비교
        for (Semver baseline : older) {
            Path initDir = Files.createDirectories(tempDir.resolve(baseline.toString()));
            Files.copy(baselineDir.resolve(baseline + ".sql"), initDir.resolve("0001_baseline.sql"));
            try (MySQLContainer<?> upgraded = freshMysql(initDir)) {
                upgraded.start();
                try (Connection conn = connect(upgraded)) {
                    for (Path release : releaseDirs.stream()
                            .filter(r -> Semver.parse(r.getFileName().toString()).compareTo(baseline) > 0)
                            .sorted(Comparator.comparing(r -> Semver.parse(r.getFileName().toString()))).toList()) {
                        for (Path file : list(release, p -> p.toString().endsWith(".sql")).sorted().toList()) {
                            log.info("[스키마 업그레이드 검증] baseline={} 적용={}/{}", baseline,
                                    release.getFileName(), file.getFileName());
                            ScriptUtils.executeSqlScript(conn, new FileSystemResource(file));
                        }
                    }
                    assertThat(snapshot(conn)).as("baseline %s 업그레이드 결과", baseline)
                            .containsExactlyElementsOf(expected);
                }
            }
        }
    }

    // information_schema 구조 스냅샷. 행 데이터는 포함하지 않으므로 schema_history 의 내용은 비교되지 않는다
    static List<String> snapshot(Connection conn) throws SQLException {
        List<String> out = new ArrayList<>();
        out.addAll(query(conn, "TABLES", """
                SELECT TABLE_NAME, ENGINE, TABLE_COLLATION FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME"""));
        out.addAll(query(conn, "COLUMNS", """
                SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLLATION_NAME
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, ORDINAL_POSITION"""));
        out.addAll(query(conn, "STATISTICS", """
                SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, INDEX_TYPE
                FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX"""));
        out.addAll(query(conn, "TABLE_CONSTRAINTS", """
                SELECT TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, CONSTRAINT_NAME"""));
        out.addAll(query(conn, "KEY_COLUMN_USAGE", """
                SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, ORDINAL_POSITION, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE()
                ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION"""));
        out.addAll(query(conn, "REFERENTIAL_CONSTRAINTS", """
                SELECT CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME, UPDATE_RULE, DELETE_RULE
                FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
                ORDER BY TABLE_NAME, CONSTRAINT_NAME"""));
        return out;
    }

    static List<String> query(Connection conn, String label, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder(label);
                for (int i = 1; i <= cols; i++) {
                    sb.append('|').append(Objects.toString(rs.getObject(i), "NULL"));
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }

    static Connection connect(MySQLContainer<?> container) throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    static Stream<Path> list(Path dir, java.util.function.Predicate<Path> filter) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(filter).toList().stream();
        }
    }

    static String stripExt(String name) {
        return name.substring(0, name.lastIndexOf('.'));
    }

    // "v1.2.3" 비교용
    record Semver(int major, int minor, int patch) implements Comparable<Semver> {
        static Semver parse(String s) {
            String[] parts = s.replaceFirst("^v", "").split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("semver 형식이 아닙니다: " + s);
            }
            return new Semver(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }

        @Override
        public int compareTo(Semver o) {
            return Comparator.comparingInt(Semver::major).thenComparingInt(Semver::minor)
                    .thenComparingInt(Semver::patch).compare(this, o);
        }

        @Override
        public String toString() {
            return "v" + major + "." + minor + "." + patch;
        }
    }
}
