package com.stagelog.photocard.application;

import com.stagelog.photocard.application.port.ImageStoragePort;
import com.stagelog.photocard.domain.StickerSetRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스티커 세트 조회. 공개 목록은 배포 기간(availableFrom <= today <= availableTo, NULL 은 열림) 안의 세트만, 관리자 목록은 전부.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StickerQuery {

    private final StickerSetRepository stickerSets;
    private final ImageStoragePort images;

    public List<StickerSetView> availableSets(LocalDate today) {
        // 한계: 세트 수가 수십 개 수준이라 전부 읽고 거른다. 수백이 되면 날짜 조건 쿼리로 바꾼다
        return stickerSets.findAllWithDetails().stream()
                .filter(set -> set.isAvailableOn(today))
                .map(set -> StickerSetView.of(set, images))
                .toList();
    }

    // 관리자 목록: 기간이 지났거나 시작 전인 세트까지 전부
    public List<StickerSetView> allSets() {
        return stickerSets.findAllWithDetails().stream().map(set -> StickerSetView.of(set, images)).toList();
    }
}
