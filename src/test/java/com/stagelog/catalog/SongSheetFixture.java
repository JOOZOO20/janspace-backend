package com.stagelog.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 테스트용 곡 마스터 xlsx 생성. 첫 행은 헤더, 이후 행은 8열 값. null 은 빈 셀, 숫자 문자열은 숫자 셀로 넣어 실제 엑셀과 같게 한다.
 */
public final class SongSheetFixture {

    public static final String[] HEADER = {
            "한국어 제목", "영어 제목", "중국어(번체) 제목", "일본어 제목", "setlist.fm 표기", "iTunes 곡 ID", "유튜브 영상 ID", "앨범명(한국어)"};

    private SongSheetFixture() {
    }

    public static byte[] xlsx(boolean withHeader, String[]... rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("songs");
            int r = 0;
            if (withHeader) {
                fill(sheet.createRow(r++), HEADER, false);
            }
            for (String[] row : rows) {
                fill(sheet.createRow(r++), row, true);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fill(Row row, String[] values, boolean numericIds) {
        for (int c = 0; c < values.length; c++) {
            if (values[c] == null) {
                continue;
            }
            // iTunes ID 열(5)은 숫자 셀. 실제 엑셀에서 숫자로 저장되는 경우를 흉내 낸다
            if (numericIds && c == 5 && values[c].matches("\\d+")) {
                row.createCell(c).setCellValue(Double.parseDouble(values[c]));
            } else {
                row.createCell(c).setCellValue(values[c]);
            }
        }
    }
}
