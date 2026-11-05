package com.stagelog.ops.application;

import com.stagelog.ops.application.port.ObjectDeletePort;
import com.stagelog.ops.domain.S3DeleteReason;
import com.stagelog.ops.domain.S3DeleteSchedule;
import com.stagelog.ops.domain.S3DeleteScheduleRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 이전 S3 key 삭제 예약과 삭제 배치 (JAN-02). 다른 기능은 교체·이전 시 schedule* 만 호출한다.
 * 이전 key 는 교체 시점부터 7일 뒤에 지운다. 7일은 브라우저·CDN 캐시나 열려 있는 화면이 옛 URL 을 참조할 수 있는 기간이다.
 * 버전 key 가 꺼져 있으면(jan.storage.versioned-key.enabled=false) key 를 덮어쓰는 기존 동작이라 예약하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageCleanup {

    static final int RETENTION_DAYS = 7;
    // 한 번 실행의 상한. 남은 예약은 다음 날 이어서 지운다
    static final int BATCH_LIMIT = 500;

    private final S3DeleteScheduleRepository schedules;
    private final List<ObjectKeyReferences> references;
    private final ObjectDeletePort storage;

    // 생성자 주입 대상이 아니라 final 이 아니다 (@RequiredArgsConstructor 가 @Value 를 옮기지 않는다)
    @Value("${jan.storage.versioned-key.enabled:true}")
    private boolean versionedKey;

    // 교체로 빠진 key 예약. 같은 key(같은 내용 재업로드), null, 제공자 URL(http) 은 S3 객체가 아니거나 그대로 쓰이므로 무시한다
    public void scheduleReplaced(String oldKey, String newKey) {
        if (isObjectKey(oldKey) && !oldKey.equals(newKey)) {
            schedule(oldKey, S3DeleteReason.REPLACED);
        }
    }

    // 고정 key -> 버전 key 백필로 복사가 끝난 key 예약
    public void scheduleMigrated(String oldKey) {
        if (isObjectKey(oldKey)) {
            schedule(oldKey, S3DeleteReason.MIGRATED);
        }
    }

    /**
     * 하루 1회. 기한(DB 시계)이 지난 예약 중 현재 DB 가 참조하지 않는 key 만 S3 에서 지우고 완료 표시한다.
     * 참조 중인 key 는 예약을 남겨 두어 다음 실행이 다시 본다. 지운 건수를 돌려준다.
     */
    public int deleteDue() {
        List<S3DeleteSchedule> due = schedules.findDue(BATCH_LIMIT);
        int deleted = 0;
        int kept = 0;
        for (S3DeleteSchedule schedule : due) {
            String key = schedule.getObjectKey();
            // ponytail: 참조 확인과 삭제 사이(수 ms)에 같은 내용이 다시 올라와 이 key 를 참조하면 지워질 수 있다. 하루 1회 배치라 창이 좁아 잠금을 두지 않는다
            if (references.stream().anyMatch(r -> r.isReferenced(key))) {
                kept++;
                continue;
            }
            try {
                // 외부 호출: S3 DeleteObject. 여러 인스턴스가 같이 돌아도 삭제·완료 표시가 멱등이다
                storage.delete(key);
                deleted += schedules.markDeleted(schedule.getId());
            } catch (RuntimeException e) {
                // 한 key 실패가 나머지 삭제를 막지 않게 한다. 예약이 남아 다음 실행이 다시 시도한다
                log.warn("[S3 이전 key 삭제 실패] key={}", key, e);
            }
        }
        log.info("[S3 이전 key 삭제] due={}, deleted={}, keptReferenced={}", due.size(), deleted, kept);
        return deleted;
    }

    private void schedule(String key, S3DeleteReason reason) {
        if (!versionedKey) {
            return;
        }
        schedules.schedule(key, RETENTION_DAYS, reason.name());
        log.info("[S3 이전 key 삭제 예약] key={}, reason={}, days={}", key, reason, RETENTION_DAYS);
    }

    private static boolean isObjectKey(String key) {
        return key != null && !key.isBlank() && !key.startsWith("http://") && !key.startsWith("https://");
    }
}
