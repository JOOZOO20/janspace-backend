-- ops: 이미지 key 버전화(JAN-02)로 교체·이전된 이전 S3 key 의 삭제 예약. 교체 시점부터 7일 뒤 삭제한다(캐시나 열린 화면이 옛 URL 을 참조할 수 있는 기간).
-- 현재 key 는 기존 컬럼 events.poster_key, albums.cover_key 에 전체 key 로 저장하므로 ALTER 하지 않는다.
CREATE TABLE s3_delete_schedule (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    object_key   VARCHAR(255) NOT NULL,
    delete_after DATETIME(6)  NOT NULL,
    deleted_at   DATETIME(6)  NULL,
    reason       VARCHAR(20)  NOT NULL,   -- REPLACED / MIGRATED
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uk_s3_delete_schedule_object_key UNIQUE (object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
