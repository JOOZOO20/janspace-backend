package com.stagelog.media;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * JAN-05 측정 S3 조건 B: 백필 1만 건 + Queue 분리 끔(jan.media.queue.split=false, 백필도 jan-media-transcode 로 발행).
 * Queue 분리 효과를 보이는 비교값이고 판정에 쓰지 않는다. 절차는 Jan05MeasurementBase.backfillScenario 로 조건 A·C 와 같다.
 */
@TestPropertySource(properties = "jan.media.queue.split=false")
class Jan05SplitOffMeasurementTest extends Jan05MeasurementBase {

    @Test
    void s3b_new_uploads_during_backfill_split_off() throws Exception {
        for (int run = 1; run <= Jan05MediaMeasurementTest.S3_RUNS; run++) {
            backfillScenario("B(백필 1만 + 분리 끔)", true, run);
        }
    }
}
