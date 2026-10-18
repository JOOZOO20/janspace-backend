-- 스키마 적용 이력. db/changes/<릴리스>/<파일> 을 기존 DB 에 적용하고 검증을 통과한 뒤에만 운영자가 한 행씩 INSERT 한다.
-- 새 DB 는 db/schema/*.sql 을 사전순으로 실행해 만들며, 마지막 9999_record_fresh.sql 이 'fresh@<태그>' 행을 남긴다.
CREATE TABLE schema_history (
    filename    VARCHAR(255) PRIMARY KEY,
    applied_at  DATETIME(6)  NOT NULL,
    applied_by  VARCHAR(128) NOT NULL,
    verified_by VARCHAR(128) NULL,
    note        VARCHAR(500) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
