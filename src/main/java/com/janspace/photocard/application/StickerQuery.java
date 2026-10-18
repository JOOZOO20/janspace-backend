package com.janspace.photocard.application;

import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.StickerSetRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 스티커 세트 조회. 배포 기간(availableFrom <= today <= availableTo, NULL 은 열림) 안의 세트만 낸다.
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
}
