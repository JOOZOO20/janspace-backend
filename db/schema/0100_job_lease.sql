-- job lease: 여러 서버가 같은 잡(공급원별 동기화)을 동시에 실행하지 않도록 DB 행 하나를 조건부 UPDATE 로 선점한다.
-- 획득은 owner_id 가 비었거나 expires_at 이 지난 행만, Heartbeat·해제는 owner_id 와 fencing_token 이 모두 맞을 때만 반영한다.
-- 시각 비교는 모두 DB 시계 NOW(6) 로 한다 (서버 시계 차이 무시).
CREATE TABLE job_leases (
    lease_key     VARCHAR(100)  NOT NULL PRIMARY KEY,   -- 예: sync:KOPIS
    owner_id      VARCHAR(100)  NULL,                   -- {hostname}:{pid}:{uuid}. NULL = 비어 있음
    fencing_token BIGINT        NOT NULL DEFAULT 0,     -- 획득마다 1 증가. 이전 소유자의 늦은 쓰기를 거르는 기준
    acquired_at   DATETIME(6)   NULL,
    heartbeat_at  DATETIME(6)   NULL,
    expires_at    DATETIME(6)   NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 획득 쿼리는 행이 있어야 동작하므로 공급원 4개를 미리 넣는다
INSERT INTO job_leases (lease_key) VALUES ('sync:NAVER'), ('sync:KOPIS'), ('sync:TICKETMASTER'), ('sync:SETLISTFM');

-- Lease 로 실행한 run 이 획득한 token. Lease 없이 기록하는 run(YOUTUBE 재생목록, setlist.fm 행사별 병합)은 NULL
ALTER TABLE sync_runs ADD COLUMN fencing_token BIGINT NULL AFTER status;
