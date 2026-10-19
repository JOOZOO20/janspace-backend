package com.stagelog.photocard.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.photocard.application.port.ImageStoragePort;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class StorageCleanupTest {

    final List<String> deleted = new ArrayList<>();
    final ImageStoragePort storage = new ImageStoragePort() {
        @Override
        public String put(String key, byte[] bytes, String contentType) {
            return key;
        }

        @Override
        public void delete(String key) {
            if (key.startsWith("fail")) {
                throw new IllegalStateException("S3 다운");
            }
            deleted.add(key);
        }

        @Override
        public String publicUrl(String key) {
            return key;
        }
    };

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @ParameterizedTest
    @DisplayName("커밋이면 onCommit 키만, 롤백이면 onRollback 키만, 결과 모름이면 아무것도 지우지 않고, 한 키 실패가 나머지를 막지 않는다")
    @CsvSource({
            TransactionSynchronization.STATUS_COMMITTED + ", old",
            TransactionSynchronization.STATUS_ROLLED_BACK + ", new",
            TransactionSynchronization.STATUS_UNKNOWN + ", ''"
    })
    void deletes_by_outcome(int status, String expected) {
        TransactionSynchronizationManager.initSynchronization();

        StorageCleanup.afterCompletion(storage, List.of("fail-old", "old"), List.of("fail-new", "new"));
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));

        assertThat(deleted).isEqualTo(expected.isEmpty() ? List.of() : List.of(expected));
    }
}
