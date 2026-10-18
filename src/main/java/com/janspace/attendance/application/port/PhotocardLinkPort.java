package com.janspace.attendance.application.port;

import java.util.Map;

/**
 * 기록 응답의 photocardId(와 별 밝기 BRIGHTEST)를 채우기 위한 포트. photocard 기능이 구현한다(photocard/infrastructure/PhotocardLinkAdapter).
 * attendance -> photocard 역방향 의존을 끊기 위한 포트이며 구현이 없으면(photocard 미탑재) 빈 맵으로 둔다.
 */
public interface PhotocardLinkPort {

    // 기록 ID -> 연결된 포토카드 ID. 연결이 없는 기록은 맵에 없다
    Map<Long, Long> photocardIdsByAttendance(Long userId);
}
