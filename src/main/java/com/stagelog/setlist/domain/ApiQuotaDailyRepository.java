package com.stagelog.setlist.domain;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiQuotaDailyRepository extends JpaRepository<ApiQuotaDaily, ApiQuotaDailyId> {

    // 영향 행 1 = 예약 성공. 한도(limit_units 의 ceilingPercent%)를 넘거나, 그날 quotaExceeded 를 받았거나, 행이 없으면 0
    @Modifying
    @Query(value = "UPDATE api_quota_daily SET reserved_units = reserved_units + :units "
            + "WHERE provider = :provider AND quota_date = :date AND bucket = :bucket AND exhausted_at IS NULL "
            + "AND used_units + reserved_units + :units <= FLOOR(limit_units * :ceilingPercent / 100)", nativeQuery = true)
    int reserve(@Param("provider") String provider, @Param("date") LocalDate date, @Param("bucket") String bucket,
                @Param("units") int units, @Param("ceilingPercent") int ceilingPercent);

    // 확정: 예약분을 사용으로 옮긴다 (CONSUMED·UNKNOWN)
    @Modifying
    @Query(value = "UPDATE api_quota_daily SET reserved_units = reserved_units - :units, used_units = used_units + :units "
            + "WHERE provider = :provider AND quota_date = :date AND bucket = :bucket", nativeQuery = true)
    int consume(@Param("provider") String provider, @Param("date") LocalDate date, @Param("bucket") String bucket, @Param("units") int units);

    // 호출하지 않은 예약분을 돌려놓는다 (RELEASED)
    @Modifying
    @Query(value = "UPDATE api_quota_daily SET reserved_units = reserved_units - :units "
            + "WHERE provider = :provider AND quota_date = :date AND bucket = :bucket", nativeQuery = true)
    int release(@Param("provider") String provider, @Param("date") LocalDate date, @Param("bucket") String bucket, @Param("units") int units);

    // 그날 모든 bucket 에 exhausted_at. 처음 찍은 호출만 영향 행이 0 보다 크다 (알림 1회 판단)
    @Modifying
    @Query(value = "UPDATE api_quota_daily SET exhausted_at = UTC_TIMESTAMP(6) "
            + "WHERE provider = :provider AND quota_date = :date AND exhausted_at IS NULL", nativeQuery = true)
    int exhaust(@Param("provider") String provider, @Param("date") LocalDate date);

    // 일일 행 미리 만들기 (스케줄러·기동 시에만. 요청 경로에서 부르지 않는다). 이미 있으면 한도를 바꾸지 않는다
    @Modifying
    @Query(value = "INSERT IGNORE INTO api_quota_daily (provider, quota_date, bucket, limit_units, used_units, reserved_units) "
            + "VALUES (:provider, :date, :bucket, :limitUnits, 0, 0)", nativeQuery = true)
    int createDay(@Param("provider") String provider, @Param("date") LocalDate date, @Param("bucket") String bucket,
                  @Param("limitUnits") int limitUnits);

    // DB 시계 NOW(6) 의 epoch 밀리초. 태평양 날짜는 이 값으로 Java 에서 계산한다 (서버 시계를 쓰지 않는다)
    @Query(value = "SELECT CAST(UNIX_TIMESTAMP(NOW(6)) * 1000 AS SIGNED)", nativeQuery = true)
    long dbNowEpochMillis();

    List<ApiQuotaDaily> findAllByIdProviderAndIdQuotaDateOrderByIdBucket(String provider, LocalDate quotaDate);
}
