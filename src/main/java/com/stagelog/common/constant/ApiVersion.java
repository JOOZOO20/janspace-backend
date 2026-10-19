package com.stagelog.common.constant;

/**
 * REST 엔드포인트 버전 prefix 상수. 모든 컨트롤러는 이 상수로 경로를 시작한다. 하드코딩한 "/api/v1" 은 쓰지 않는다.
 */
public final class ApiVersion {

    public static final String V1_BASE = "/api/v1";
    // V2·V3 는 예약 (docs/conventions/api.md §1). 버전 이동은 사용자가 직접 하며 지금은 쓰는 컨트롤러가 없다
    public static final String V2_BASE = "/api/v2";
    public static final String V3_BASE = "/api/v3";
    // 테스트·내부용. 사용하는 컨트롤러는 @Profile({"develop","local"}) 필수
    public static final String TEST_BASE = "/api/test";

    private ApiVersion() {
    }
}
