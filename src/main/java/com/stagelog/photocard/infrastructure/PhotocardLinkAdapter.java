package com.stagelog.photocard.infrastructure;

import com.stagelog.attendance.application.port.PhotocardLinkPort;
import com.stagelog.photocard.application.PhotocardQuery;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * attendance 의 PhotocardLinkPort 구현. 기록 응답의 photocardId 를 photocard 조회로 채운다 (photocard -> attendance 단방향).
 */
@Component
@RequiredArgsConstructor
public class PhotocardLinkAdapter implements PhotocardLinkPort {

    private final PhotocardQuery photocardQuery;

    @Override
    public Map<Long, Long> photocardIdsByAttendance(Long userId) {
        return photocardQuery.photocardIdsByAttendance(userId);
    }
}
