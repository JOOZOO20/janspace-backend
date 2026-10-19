package com.stagelog.catalog.application;

import java.util.List;

/**
 * 엑셀 import 결과. errors 는 행 단위 실패 사유이며 실패한 행만 건너뛰고 나머지는 저장된다.
 */
public record ImportReport(int created, int updated, int skipped, List<String> errors) {
}
