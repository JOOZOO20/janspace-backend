package com.janspace.photocard.application;

import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.MonthlyQuotaPolicy;
import com.janspace.photocard.domain.Photocard;
import com.janspace.photocard.domain.PhotocardRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포토카드 조회. linkedAttendanceIds·photocardIdsByAttendance 는 attendance 의 별 밝기·photocardId 계산에 공개하는 계약이다.
 * attendance 가 photocard 를 직접 부르지 않도록 attendance 의 PhotocardLinkPort 를 infrastructure.PhotocardLinkAdapter 가 이걸로 구현한다.
 */
@Service
@Transactional(readOnly = true)
public class PhotocardQuery {

    private final PhotocardRepository photocards;
    private final ImageStoragePort images;
    private final int perMonth;

    public PhotocardQuery(PhotocardRepository photocards, ImageStoragePort images,
                          @Value("${janspace.photocard.per-month:10}") int perMonth) {
        this.photocards = photocards;
        this.images = images;
        this.perMonth = perMonth;
    }

    // 내 바인더: 최신순
    public List<PhotocardView> mine(Long userId) {
        return photocards.findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(userId).stream().map(c -> PhotocardView.of(c, images)).toList();
    }

    // 이번 달(UTC) 남은 장 수. 미삭제 장만 센다(지운 장은 돌려받는다)
    public PhotocardQuota quota(Long userId) {
        MonthlyQuotaPolicy.Window month = MonthlyQuotaPolicy.monthOf(Instant.now());
        long used = photocards.countByUserIdAndDeletedAtIsNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(userId, month.from(), month.to());
        return new PhotocardQuota(perMonth, MonthlyQuotaPolicy.remaining(used, perMonth), month.to());
    }

    public Set<Long> linkedAttendanceIds(Long userId) {
        return photocardIdsByAttendance(userId).keySet();
    }

    // 기록 ID -> 포토카드 ID. 기록 하나에 카드는 한 장이다(attendance_id 유일)
    public Map<Long, Long> photocardIdsByAttendance(Long userId) {
        Map<Long, Long> out = new HashMap<>();
        for (Photocard card : photocards.findAllByUserIdAndDeletedAtIsNull(userId)) {
            if (card.getAttendanceId() != null) {
                out.put(card.getAttendanceId(), card.getId());
            }
        }
        return out;
    }
}
