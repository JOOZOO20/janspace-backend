package com.janspace.photocard.application;

import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.Photocard;
import com.janspace.photocard.domain.PhotocardRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포토카드 조회. linkedAttendanceIds·photocardIdsByAttendance 는 attendance 의 별 밝기·photocardId 계산에 공개하는 계약이다.
 * attendance 가 photocard 를 직접 부르지 않도록 attendance 의 PhotocardLinkPort 를 infrastructure.PhotocardLinkAdapter 가 이걸로 구현한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhotocardQuery {

    private final PhotocardRepository photocards;
    private final ImageStoragePort images;

    // 내 바인더: 최신순
    public List<PhotocardView> mine(Long userId) {
        return photocards.findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(userId).stream().map(c -> PhotocardView.of(c, images)).toList();
    }

    public Set<Long> linkedAttendanceIds(Long userId) {
        return photocardIdsByAttendance(userId).keySet();
    }

    // 기록 ID -> 포토카드 ID. 한 기록에 여러 장이면 가장 최근(id 큰) 것
    public Map<Long, Long> photocardIdsByAttendance(Long userId) {
        Map<Long, Long> out = new HashMap<>();
        for (Photocard card : photocards.findAllByUserIdAndDeletedAtIsNull(userId)) {
            if (card.getAttendanceId() != null) {
                out.merge(card.getAttendanceId(), card.getId(), Math::max);
            }
        }
        return out;
    }
}
