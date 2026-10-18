package com.janspace.event.infrastructure;

import com.janspace.event.application.port.TimezonePort;
import java.time.ZoneId;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.iakovlev.timeshape.TimeZoneEngine;
import org.springframework.stereotype.Component;

/**
 * timeshape(오프라인 경계 데이터) 로 좌표 -> 시간대. 전 세계 데이터 초기화가 수 초·수백 MB 라 첫 호출 때 한 번만 올린다.
 */
@Slf4j
@Component
public class TimeshapeTimezoneAdapter implements TimezonePort {

    private volatile TimeZoneEngine engine;

    @Override
    public Optional<ZoneId> resolve(double lat, double lng) {
        return engine().query(lat, lng);
    }

    private TimeZoneEngine engine() {
        TimeZoneEngine local = engine;
        if (local == null) {
            synchronized (this) {
                if (engine == null) {
                    long started = System.currentTimeMillis();
                    engine = TimeZoneEngine.initialize();
                    log.info("[timeshape 초기화] elapsedMs={}", System.currentTimeMillis() - started);
                }
                local = engine;
            }
        }
        return local;
    }
}
