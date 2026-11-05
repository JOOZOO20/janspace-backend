-- setlist: 외부 API Quota 원장 (JAN-03). 지금은 YouTube Data API 만 쓴다. 날짜는 YouTube Quota 리셋 기준인 태평양 시간(America/Los_Angeles) 날짜다.
-- 일일 행은 스케줄러가 전날(그리고 기동 시 오늘·내일) 미리 만든다. 예약은 조건부 UPDATE 한 줄로 한도를 넘지 않을 때만 성공한다
CREATE TABLE api_quota_daily (
    provider       VARCHAR(20) NOT NULL,                 -- YOUTUBE
    quota_date     DATE        NOT NULL,                 -- 태평양 시간 기준 날짜
    bucket         VARCHAR(10) NOT NULL,                 -- AUTO / MANUAL
    limit_units    INT         NOT NULL,
    used_units     INT         NOT NULL DEFAULT 0,       -- CONSUMED + UNKNOWN
    reserved_units INT         NOT NULL DEFAULT 0,       -- 예약했지만 아직 호출 결과가 없는 몫
    exhausted_at   DATETIME(6) NULL,                     -- API 가 quotaExceeded 를 돌려준 시각. 찍히면 그날 예약이 모두 실패한다
    PRIMARY KEY (provider, quota_date, bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 호출 한 번 = 행 하나. 예약 때 RESERVED 로 만들고, 응답을 받으면 CONSUMED(실패 응답 포함), 결과를 모르면 UNKNOWN, 호출하지 않으면 RELEASED
CREATE TABLE api_quota_ledger (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    provider    VARCHAR(20) NOT NULL,
    quota_date  DATE        NOT NULL,
    bucket      VARCHAR(10) NOT NULL,
    operation   VARCHAR(10) NOT NULL,                    -- LIST / INSERT / UPDATE / DELETE
    units       INT         NOT NULL,
    status      VARCHAR(10) NOT NULL,                    -- RESERVED / CONSUMED / UNKNOWN / RELEASED
    event_id    BIGINT      NULL,
    sync_run_id BIGINT      NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_date_event (quota_date, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Quota 부족으로 미룬(DEFERRED) 재생목록 동기화. 리셋 뒤 스케줄러가 이 값이 있는 행사를 우선순위대로 다시 동기화하고, 성공하면 NULL 로 되돌린다
ALTER TABLE setlist_pages ADD COLUMN youtube_sync_deferred_at DATETIME(6) NULL AFTER youtube_playlist_id;
