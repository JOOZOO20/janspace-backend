-- ops: 외부 출처 동기화 실행 이력. 각 동기화(네이버·KOPIS·Ticketmaster·setlist.fm·YouTube 재생목록)는 시작 시 RUNNING 행을 남기고 끝나면 OK/FAILED 로 닫는다.
CREATE TABLE sync_runs (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source      VARCHAR(20)   NOT NULL,   -- NAVER / KOPIS / TICKETMASTER / SETLISTFM / YOUTUBE
    started_at  DATETIME(6)   NOT NULL,
    finished_at DATETIME(6)   NULL,
    status      VARCHAR(20)   NOT NULL,   -- RUNNING / OK / FAILED
    summary     VARCHAR(1000) NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
