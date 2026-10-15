package com.janspace.photocard.application;

import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.FrameRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 네컷 프레임 조회. 공개 목록은 상시 프레임 + 오늘이 배포 기간 안인 스페셜, 관리자 목록은 삭제 안 된 전부.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FrameQuery {

    private final FrameRepository frames;
    private final ImageStoragePort images;

    // 한계: 프레임 수가 수십 개 수준이라 전부 읽고 거른다
    public List<FrameView> available(LocalDate today) {
        return frames.findAllByDeletedAtIsNullOrderByIdAsc().stream()
                .filter(frame -> frame.isAvailableOn(today))
                .map(frame -> FrameView.of(frame, images))
                .toList();
    }

    public List<FrameView> all() {
        return frames.findAllByDeletedAtIsNullOrderByIdAsc().stream().map(frame -> FrameView.of(frame, images)).toList();
    }
}
