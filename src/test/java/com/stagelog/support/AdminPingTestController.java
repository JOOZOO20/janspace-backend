package com.stagelog.support;

import com.stagelog.common.constant.ApiVersion;
import com.stagelog.common.dto.ResponseDTO;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/admin/** 의 ADMIN 권한 규칙을 검증하기 위한 테스트 전용 엔드포인트.
 */
@RestController
@Profile("test")
@RequestMapping(ApiVersion.V1_BASE + "/admin/ping")
public class AdminPingTestController {

    @GetMapping
    public ResponseDTO<Void> ping() {
        return ResponseDTO.ok();
    }
}
