package com.janspace.notification.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.notification.domain.PushDelivery;
import com.janspace.notification.domain.PushDeliveryRepository;
import com.janspace.notification.domain.PushJob;
import com.janspace.notification.domain.PushJobRepository;
import com.janspace.notification.domain.PushJobStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 잡·배달 조회.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PushJobQuery {

    private final PushJobRepository jobs;
    private final PushDeliveryRepository deliveries;

    // 최신(id 내림차순) size 건. status 가 null 이면 전체
    public List<PushJob> list(PushJobStatus status, int size) {
        PageRequest page = PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "id"));
        return status == null ? jobs.findAll(page).getContent() : jobs.findAllByStatus(status, page);
    }

    public PushJob job(Long jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new NotFoundException(ErrorCode.PUSH_JOB_NOT_FOUND));
    }

    public List<PushDelivery> deliveries(Long jobId) {
        return deliveries.findAllByJobId(jobId);
    }
}
