package com.janspace.event.application.port;

import java.time.ZoneId;
import java.util.Optional;

/**
 * 좌표 -> IANA 시간대. 바다·미지 좌표는 empty.
 */
public interface TimezonePort {

    Optional<ZoneId> resolve(double lat, double lng);
}
