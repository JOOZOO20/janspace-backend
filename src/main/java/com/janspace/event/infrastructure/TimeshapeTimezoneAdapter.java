package com.janspace.event.infrastructure;

import com.janspace.event.application.port.TimezonePort;
import java.time.ZoneId;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.iakovlev.timeshape.TimeZoneEngine;
import org.springframework.stereotype.Component;

/**
 * timeshape(오프라인 경계 데이터) 로 좌표 -> 시간대. 전 세계 데이터 초기화가 수 초·수백 MB 라 첫 호출 때 한 번만 올린다.
 *
 * 외부 API(Google Time Zone 등) 대신 오프라인 데이터를 쓴 이유:
 * - 조회 시점이 동기화 트랜잭션 안(공연장 해석 -> 행사 시간대 접기)이라 네트워크 호출·키·요금·레이트 리밋을 끼우고 싶지 않았다
 * - 공연장 시간대는 한 번 채우면 거의 바뀌지 않아, 호출당 과금 API 의 이점(최신 경계)이 작다. 경계 갱신은 의존성 버전(2025b)으로 받는다
 * - 같은 좌표에 항상 같은 답이라 테스트가 결정적이다
 * 대가: jar 약 24MB, 초기화 수 초와 힙(테스트 maxHeapSize 2g). 그래서 지연 초기화하고, Ticketmaster 처럼 제공자가 IANA 시간대를
 * 직접 주면 이 조회를 건너뛴다(VenueCommand.resolveFromSource).
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
