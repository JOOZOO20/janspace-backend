-- ops: 수집과 후처리 분리(JAN-04). 출처 저장과 같은 트랜잭션에 발행할 메시지를 기록하고(Transactional Outbox), Relay 가 SQS 로 발행한다.
-- Relay 는 PENDING 을 FOR UPDATE SKIP LOCKED 로 가져가 발행 성공 응답 뒤에만 PUBLISHED 로 바꾼다. SQS 가 거부한 메시지(본문 오류 등)만 FAILED.
CREATE TABLE outbox_events (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message_id   CHAR(36)     NOT NULL,             -- payload.messageId 와 같다. Consumer 중복 판정·실패 기록·DLQ redrive 의 키
    queue_name   VARCHAR(50)  NOT NULL,             -- jan-source-apply / jan-poster-ingest / (JAN-05) jan-media-transcode
    payload      JSON         NOT NULL,
    status       VARCHAR(20)  NOT NULL,             -- PENDING / PUBLISHED / FAILED
    attempts     INT          NOT NULL DEFAULT 0,   -- 발행 실패 횟수
    published_at DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uk_outbox_events_message_id UNIQUE (message_id),
    KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 동기화 실행 안 항목 단위 실패 (KOPIS 건별 격리). 실패 항목이 있으면 실행은 PARTIAL 이고 다음 실행이 같은 구간을 다시 가져온다
CREATE TABLE sync_item_failures (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sync_run_id BIGINT        NOT NULL,
    provider    VARCHAR(20)   NOT NULL,
    external_id VARCHAR(100)  NOT NULL,             -- 실패한 항목의 식별자 (KOPIS 는 행사 ID 또는 mt20id)
    error_code  VARCHAR(50)   NOT NULL,             -- 예외 클래스 이름
    message     VARCHAR(1000) NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    KEY idx_run (sync_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Queue 메시지 처리 실패 (메시지당 1행, 실패마다 attempts 증가). DLQ 메시지에는 오류 정보가 없어 관리 API 가 여기서 마지막 오류를 읽는다
CREATE TABLE queue_message_failures (
    message_id     CHAR(36)      NOT NULL PRIMARY KEY,
    queue_name     VARCHAR(50)   NOT NULL,
    attempts       INT           NOT NULL,
    last_error     VARCHAR(1000) NOT NULL,
    permanent      BOOL          NOT NULL,          -- 영구 실패(재시도 없이 삭제)면 TRUE
    last_failed_at DATETIME(6)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- sync_runs.status 는 VARCHAR 라 PARTIAL(JAN-03 에서 추가)은 코드만 바뀐다
