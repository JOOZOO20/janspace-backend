package com.stagelog.event.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * timeshape 오프라인 데이터로 좌표 -> 시간대. 전 세계 초기화라 수 초 걸린다.
 */
class TimeshapeTimezoneAdapterTest {

    @Test
    @DisplayName("방콕·서울 좌표는 각 시간대, 태평양 한가운데는 해상 시간대(Etc/GMT±N)")
    void resolves_known_points() {
        TimeshapeTimezoneAdapter adapter = new TimeshapeTimezoneAdapter();

        assertThat(adapter.resolve(13.7563, 100.5018)).contains(ZoneId.of("Asia/Bangkok"));
        assertThat(adapter.resolve(37.5199, 127.1233)).contains(ZoneId.of("Asia/Seoul"));
        // 2025b 데이터는 바다도 Etc/GMT±N 으로 덮는다
        assertThat(adapter.resolve(0.0, -160.0)).get().extracting(ZoneId::getId).asString().startsWith("Etc/GMT");
    }
}
