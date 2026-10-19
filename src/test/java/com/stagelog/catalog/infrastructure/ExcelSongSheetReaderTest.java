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
                new String[] {"떠도는 별들을 위해", "For Stars Who Wander", "為流浪的星星們", "さまよう星たちのために",
                        "for stars who wander|For Stars", "1900000101", "abc123", "등대"},
                new String[] {"LIGHTHOUSE", null, null, null, null, null, null, null}));

        List<SongSheetRow> rows;
        try (InputStream in = Files.newInputStream(file)) {
            rows = reader.read(in);
        }

        assertThat(rows).hasSize(2);
        SongSheetRow first = rows.get(0);
        assertThat(first.rowNumber()).isEqualTo(2);
        assertThat(first.ko()).isEqualTo("떠도는 별들을 위해");
        assertThat(first.en()).isEqualTo("For Stars Who Wander");
        assertThat(first.zhTw()).isEqualTo("為流浪的星星們");
        assertThat(first.ja()).isEqualTo("さまよう星たちのために");
        assertThat(first.aliases()).isEqualTo("for stars who wander|For Stars");
        assertThat(first.itunesTrackId()).isEqualTo("1900000101");
        assertThat(first.youtubeVideoId()).isEqualTo("abc123");
        assertThat(first.albumTitle()).isEqualTo("등대");
        assertThat(rows.get(1).ko()).isEqualTo("LIGHTHOUSE");
        assertThat(rows.get(1).en()).isEmpty();
        assertThat(rows.get(1).itunesTrackId()).isEmpty();
    }

    @Test
    @DisplayName("헤더 행이 없으면 CATALOG_IMPORT_INVALID")
    void rejects_missing_header() {
        byte[] noHeader = SongSheetFixture.xlsx(false, new String[] {"LIGHTHOUSE", null, null, null, null, null, null, null});
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
