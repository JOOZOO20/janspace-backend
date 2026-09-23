-- notification: 웹 푸시 구독·잡·배달.
-- push_subscriptions 는 push_deliveries 가 FK 로 참조하므로 물리 삭제 대신 소프트 삭제하고, 같은 endpoint 재구독 시 되살린다.
CREATE TABLE push_subscriptions (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    endpoint    VARCHAR(512)  NOT NULL,                 -- 브라우저 PushSubscriptionJSON.endpoint
    p256dh      VARCHAR(255)  NOT NULL,                 -- keys.p256dh (base64url)
    auth        VARCHAR(255)  NOT NULL,                 -- keys.auth (base64url)
    user_agent  VARCHAR(255)  NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    deleted_at  DATETIME(6)   NULL,                     -- 410 GONE·탈퇴 시 소프트 삭제
    CONSTRAINT uk_push_subscriptions_endpoint UNIQUE (endpoint),
    CONSTRAINT fk_push_subscriptions_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 행사당 종류(RECORD / RECORD_FALLBACK / SETLIST_CONFIRMED)별 1건. 선점·물질화·완료는 조건부 UPDATE 로만 전이한다.
CREATE TABLE push_jobs (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id        BIGINT        NOT NULL,
    kind            VARCHAR(30)   NOT NULL,             -- RECORD / RECORD_FALLBACK / SETLIST_CONFIRMED
    due_at          DATETIME(6)   NOT NULL,             -- UTC
    status          VARCHAR(20)   NOT NULL,             -- SCHEDULED / DISPATCHING / DONE / CANCELLED
    claimed_at      DATETIME(6)   NULL,                 -- 디스패처가 선점한 순간
    materialized_at DATETIME(6)   NULL,                 -- 배달 집합이 확정된 순간. 이후 대상·구독을 다시 계산하지 않는다
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    CONSTRAINT uk_push_jobs_event_kind UNIQUE (event_id, kind),
    CONSTRAINT fk_push_jobs_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 잡 x 구독 1건. 자동 전송 시도 최대 3회 + 관리자 수동 재시도.
CREATE TABLE push_deliveries (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id              BIGINT        NOT NULL,
    subscription_id     BIGINT        NOT NULL,
    user_id             BIGINT        NOT NULL,
    status              VARCHAR(20)   NOT NULL,         -- PENDING / SENDING / SENT / RETRY / FAILED / GONE / CANCELLED
    attempts            INT           NOT NULL DEFAULT 0,  -- 선점(SENDING 전이)마다 +1. 고아 회수도 소모한 것으로 센다
    next_attempt_at     DATETIME(6)   NULL,             -- RETRY 백오프(1분·5분·30분) 후 시각
    sending_started_at  DATETIME(6)   NULL,             -- 5분 넘게 SENDING 이면 고아로 회수
    last_error          VARCHAR(500)  NULL,
    sent_at             DATETIME(6)   NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    CONSTRAINT uk_push_deliveries_job_subscription UNIQUE (job_id, subscription_id),
    CONSTRAINT fk_push_deliveries_job FOREIGN KEY (job_id) REFERENCES push_jobs (id),
    CONSTRAINT fk_push_deliveries_subscription FOREIGN KEY (subscription_id) REFERENCES push_subscriptions (id),
    CONSTRAINT fk_push_deliveries_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
