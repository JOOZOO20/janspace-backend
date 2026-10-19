package com.stagelog.catalog.infrastructure;

import com.stagelog.catalog.application.port.SongSheetReader;
import com.stagelog.catalog.application.port.SongSheetRow;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Apache POI 로 xlsx 첫 시트를 읽는다. 1행은 헤더(첫 셀에 "한국어" 포함)여야 하며 2행부터 데이터다.
 */
@Slf4j
@Component
public class ExcelSongSheetReader implements SongSheetReader {

    static final String[] HEADERS = {
            "한국어 제목", "영어 제목", "중국어(번체) 제목", "일본어 제목", "setlist.fm 표기", "iTunes 곡 ID", "유튜브 영상 ID", "앨범명(한국어)"};

    @Override
    public List<SongSheetRow> read(InputStream xlsx) {
        try (Workbook workbook = new XSSFWorkbook(xlsx)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null || !text(header.getCell(0)).contains("한국어")) {
                throw new BadRequestException(ErrorCode.CATALOG_IMPORT_INVALID,
                        "1행은 헤더여야 합니다: " + String.join(" | ", HEADERS));
            }
            List<SongSheetRow> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                rows.add(new SongSheetRow(i + 1, text(row.getCell(0)), text(row.getCell(1)), text(row.getCell(2)),
                        text(row.getCell(3)), text(row.getCell(4)), text(row.getCell(5)), text(row.getCell(6)),
                        text(row.getCell(7))));
            }
            log.info("[곡 마스터 엑셀 읽기] rows={}", rows.size());
            return rows;
        } catch (IOException | IllegalArgumentException e) {
            // POI 는 xlsx 가 아닌 파일을 IllegalArgumentException/NotOfficeXmlFileException 으로 거부한다
            log.warn("[곡 마스터 엑셀 읽기 실패] reason={}", e.getMessage());
            throw new BadRequestException(ErrorCode.CATALOG_IMPORT_INVALID);
        }
    }

    // 숫자 셀(iTunes ID)은 지수 표기 없이 정수 문자열로. 비어 있으면 ""
    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double value = cell.getNumericCellValue();
                yield value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
            }
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
