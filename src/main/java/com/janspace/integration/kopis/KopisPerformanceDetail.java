package com.janspace.integration.kopis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 공연상세(pblprfr/{mt20id}) 의 db 항목. relates 는 예매처 목록, dtguidance 는 요일별 공연 시각 문자열.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KopisPerformanceDetail(String mt20id, String prfnm, String prfpdfrom, String prfpdto, String fcltynm, String mt10id,
                                     String mt13id, String prfcast, String prfruntime, String prfage, String pcseguidance,
                                     String poster, String dtguidance, String prfstate, String updatedate, Relates relates) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Relates(List<KopisRelate> relate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KopisRelate(String relatenm, String relateurl) {
    }

    public List<KopisRelate> relateList() {
        return relates == null || relates.relate() == null ? List.of() : relates.relate();
    }
}
