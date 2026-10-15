package com.janspace.photocard.application;

import com.janspace.photocard.application.port.ImageStoragePort;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 저장소 객체 정리를 DB 결과가 확정된 뒤로 미룬다. 커밋 전에 지우면 롤백 시 행이 지워진 객체를 가리키게 되고,
 * 롤백된 요청이 올린 객체는 어떤 행도 가리키지 않으므로 지운다. 정리 실패는 요청을 실패시키지 않는다(WARN 만).
 */
@Slf4j
final class StorageCleanup {

    private StorageCleanup() {
    }

    // 현재 트랜잭션 안에서 불러야 한다
    static void afterCompletion(ImageStoragePort storage, List<String> onCommit, List<String> onRollback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // 결과를 모르는 경우(STATUS_UNKNOWN)는 어느 쪽도 지우지 않는다
                if (status == STATUS_COMMITTED) {
                    onCommit.forEach(key -> deleteQuietly(storage, key));
                } else if (status == STATUS_ROLLED_BACK) {
                    onRollback.forEach(key -> deleteQuietly(storage, key));
                }
            }
        });
    }

    private static void deleteQuietly(ImageStoragePort storage, String key) {
        try {
            storage.delete(key);
            log.info("[저장소 객체 삭제] key={}", key);
        } catch (RuntimeException e) {
            log.warn("[저장소 객체 삭제 실패] key={}", key, e);
        }
    }
}
