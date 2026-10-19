package com.stagelog.integration.kopis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 공연목록(pblprfr) 의 db 항목. 날짜는 "yyyy.MM.dd".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KopisPerformance(String mt20id, String prfnm, String prfpdfrom, String prfpdto, String fcltynm, String poster,
                               String area, String genrenm, String prfstate) {
}
