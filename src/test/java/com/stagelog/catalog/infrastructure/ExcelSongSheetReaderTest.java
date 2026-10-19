package com.stagelog.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stagelog.catalog.SongSheetFixture;
import com.stagelog.catalog.application.port.SongSheetRow;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcelSongSheetReaderTest {

    final ExcelSongSheetReader reader = new ExcelSongSheetReader();

    @Test
    @DisplayName("POI 로 만든 xlsx 를 8열 행으로 읽는다. 숫자 셀 iTunes ID 는 정수 문자열, 빈 셀은 빈 문자열")
    void reads_rows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("songs.xlsx");
        Files.write(file, SongSheetFixture.xlsx(true,
                new String[] {"주저하는 연인들을 위해", "For Lovers Who Hesitate", "為猶豫的戀人們", "ためらう恋人たちのために",
                        "for lovers who hesitate|For Lovers", "1456790871", "abc123", "전설"},
                new String[] {"LEGEND", null, null, null, null, null, null, null}));

        List<SongSheetRow> rows;
        try (InputStream in = Files.newInputStream(file)) {
            rows = reader.read(in);
        }

        assertThat(rows).hasSize(2);
        SongSheetRow first = rows.get(0);
        assertThat(first.rowNumber()).isEqualTo(2);
        assertThat(first.ko()).isEqualTo("주저하는 연인들을 위해");
        assertThat(first.en()).isEqualTo("For Lovers Who Hesitate");
        assertThat(first.zhTw()).isEqualTo("為猶豫的戀人們");
        assertThat(first.ja()).isEqualTo("ためらう恋人たちのために");
        assertThat(first.aliases()).isEqualTo("for lovers who hesitate|For Lovers");
        assertThat(first.itunesTrackId()).isEqualTo("1456790871");
        assertThat(first.youtubeVideoId()).isEqualTo("abc123");
        assertThat(first.albumTitle()).isEqualTo("전설");
        assertThat(rows.get(1).ko()).isEqualTo("LEGEND");
        assertThat(rows.get(1).en()).isEmpty();
        assertThat(rows.get(1).itunesTrackId()).isEmpty();
    }

    @Test
    @DisplayName("헤더 행이 없으면 CATALOG_IMPORT_INVALID")
    void rejects_missing_header() {
        byte[] noHeader = SongSheetFixture.xlsx(false, new String[] {"LEGEND", null, null, null, null, null, null, null});
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(noHeader)))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CATALOG_IMPORT_INVALID);
    }

    @Test
    @DisplayName("xlsx 가 아니면 CATALOG_IMPORT_INVALID")
    void rejects_non_xlsx() {
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream("not an excel file".getBytes())))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CATALOG_IMPORT_INVALID);
    }
}
