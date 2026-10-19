package com.stagelog.catalog.application.port;

import java.io.InputStream;
import java.util.List;

/**
 * 곡 마스터 엑셀을 행으로 읽는다. 셀은 문자열 그대로 넘기고 해석(숫자 변환, 별칭 분리)은 application 이 한다.
 * 파일 형식·헤더가 잘못되면 CATALOG_IMPORT_INVALID 를 던진다.
 */
public interface SongSheetReader {

    List<SongSheetRow> read(InputStream xlsx);
}
