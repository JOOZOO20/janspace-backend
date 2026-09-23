-- event: 행사(회차 단위)·공연장·번역·출처·매칭 후보·발견·출처 변경 확인 큐·팬 제안. 설계.
-- 시각 규칙: 벽시계 시각은 DATETIME(0) + 같은 행의 timezone, UTC 파생 순간은 DATETIME(6), 순수 날짜는 DATE (entity.md §3(g)).
-- JSON 컬럼(locked_fields, raw)은 내용으로 조회하지 않는다 (entity.md §3(h)).

CREATE TABLE venues (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(255)  NOT NULL,             -- 원어 이름
    original_locale VARCHAR(10)   NOT NULL,             -- name 의 로케일 (ko / en / zh-TW / ja)
    city            VARCHAR(255)  NULL,
    country_code    VARCHAR(2)    NULL,                 -- ISO 3166-1 alpha-2
    lat             DECIMAL(9,6)  NULL,
    lng             DECIMAL(9,6)  NULL,
    timezone        VARCHAR(64)   NULL,                 -- IANA. 좌표에서 TimezonePort 로 채움, 관리자 잠금 가능
    locked_fields   JSON          NOT NULL,             -- VenueLockableField 이름 배열 (NAME / CITY / COORDINATES / TIMEZONE)
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE venue_translations (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    venue_id    BIGINT        NOT NULL,
    field       VARCHAR(20)   NOT NULL,                 -- NAME / CITY
    locale      VARCHAR(10)   NOT NULL,                 -- BCP-47 정규형
    text        VARCHAR(500)  NOT NULL,
    source      VARCHAR(20)   NOT NULL,                 -- PROVIDER / MACHINE / ADMIN
    reviewed    BOOL          NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT uk_venue_translations UNIQUE (venue_id, field, locale),
    CONSTRAINT fk_venue_translations_venue FOREIGN KEY (venue_id) REFERENCES venues (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE venue_sources (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    venue_id    BIGINT        NOT NULL,
    provider    VARCHAR(20)   NOT NULL,                 -- KOPIS / TICKETMASTER / SETLISTFM
    external_id VARCHAR(100)  NOT NULL,
    raw         JSON          NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT uk_venue_sources UNIQUE (provider, external_id),
    CONSTRAINT fk_venue_sources_venue FOREIGN KEY (venue_id) REFERENCES venues (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 한 행 = 공연 회차 하나. 같은 날 두 회차면 두 행 (event_date 같고 starts_at_local 다름).
-- 불변식: starts_at_local IS NOT NULL 이면 event_date = DATE(starts_at_local). starts_at / ends_at 은 Event.recomputeInstants() 파생.
CREATE TABLE events (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    venue_id        BIGINT        NULL,
    name            VARCHAR(255)  NOT NULL,             -- 원어 이름
    original_locale VARCHAR(10)   NOT NULL,
    description     TEXT          NULL,
    event_date      DATE          NOT NULL,             -- 공연장 현지 날짜
    starts_at_local DATETIME(0)   NULL,                 -- 공연장 현지 벽시계 (원본)
    ends_at_local   DATETIME(0)   NULL,                 -- 공연장 현지 벽시계 (관리자만 입력)
    timezone        VARCHAR(64)   NOT NULL DEFAULT 'Asia/Seoul',  -- IANA. 공연장 timezone 에서 접힘, 잠금 가능
    starts_at       DATETIME(6)   NULL,                 -- UTC 파생
    ends_at         DATETIME(6)   NULL,                 -- UTC 파생
    running_minutes INT           NULL,
    type            VARCHAR(20)   NOT NULL,             -- CONCERT / FESTIVAL / UNIVERSITY / FREE / BROADCAST / OTHER
    season          VARCHAR(100)  NULL,
    status          VARCHAR(20)   NOT NULL,             -- ACTIVE / CANCEL_SUSPECTED / CANCELLED (생명주기만. 완성도는 응답에서 합성)
    poster_key      VARCHAR(2048) NULL,                 -- S3 키 또는 제공자 포스터 절대 URL
    ticket_url      VARCHAR(2048) NULL,
    locked_fields   JSON          NOT NULL,             -- EventLockableField 이름 배열
    last_alerted_on DATE          NULL,                 -- D-3 시각 미입력 알림을 보낸 날 (하루 1회 멱등)
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    deleted_at      DATETIME(6)   NULL,
    CONSTRAINT fk_events_venue FOREIGN KEY (venue_id) REFERENCES venues (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_translations (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id    BIGINT        NOT NULL,
    field       VARCHAR(20)   NOT NULL,                 -- NAME / DESCRIPTION
    locale      VARCHAR(10)   NOT NULL,
    text        TEXT          NOT NULL,
    source      VARCHAR(20)   NOT NULL,                 -- PROVIDER / MACHINE / ADMIN
    reviewed    BOOL          NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT uk_event_translations UNIQUE (event_id, field, locale),
    CONSTRAINT fk_event_translations_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 확정된 출처 연결. raw 에는 제공자 원본이 아니라 정규화된 SourceSnapshot JSON 을 둔다 (잠금 해제 시 제공자를 몰라도 다시 접기 위해).
CREATE TABLE event_sources (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id       BIGINT        NOT NULL,
    provider       VARCHAR(20)   NOT NULL,              -- NAVER / KOPIS / TICKETMASTER / SETLISTFM
    external_id    VARCHAR(100)  NOT NULL,
    occurrence_key VARCHAR(20)   NOT NULL,              -- 'YYYY-MM-DD' 또는 'YYYY-MM-DD#HH:mm'
    raw            JSON          NOT NULL,
    fetched_at     DATETIME(6)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT uk_event_sources_external UNIQUE (provider, external_id, occurrence_key),
    CONSTRAINT uk_event_sources_event_provider UNIQUE (event_id, provider),
    CONSTRAINT fk_event_sources_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 기존 행사에 대한 후보가 둘 이상일 때. 관리자가 하나를 고르면 event_sources 로 옮기고 나머지는 지운다.
CREATE TABLE event_match_candidates (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id       BIGINT        NOT NULL,
    provider       VARCHAR(20)   NOT NULL,
    external_id    VARCHAR(100)  NOT NULL,
    occurrence_key VARCHAR(20)   NOT NULL,
    raw            JSON          NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT uk_event_match_candidates UNIQUE (event_id, provider, external_id, occurrence_key),
    CONSTRAINT fk_event_match_candidates_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 내부 행사가 없는 외부 회차. 관리자가 새 행사로 만들거나 기존 행사에 붙인다.
CREATE TABLE event_discoveries (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider       VARCHAR(20)   NOT NULL,
    external_id    VARCHAR(100)  NOT NULL,
    occurrence_key VARCHAR(20)   NOT NULL,
    raw            JSON          NOT NULL,
    event_date     DATE          NOT NULL,
    venue_name     VARCHAR(255)  NULL,
    city           VARCHAR(255)  NULL,
    status         VARCHAR(20)   NOT NULL,              -- PENDING / ACCEPTED / DISMISSED
    event_id       BIGINT        NULL,                  -- 수락 후 연결된 행사
    resolved_at    DATETIME(6)   NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT uk_event_discoveries UNIQUE (provider, external_id, occurrence_key),
    CONSTRAINT fk_event_discoveries_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 민감 필드(날짜·시각·공연장)의 값 → 다른 값 변경 확인 큐. 애플리케이션 규칙: (event_id, field) 당 PENDING 최대 1건.
CREATE TABLE event_source_changes (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id       BIGINT        NOT NULL,
    field          VARCHAR(20)   NOT NULL,              -- EVENT_DATE / STARTS_AT / VENUE
    current_value  VARCHAR(255)  NULL,
    proposed_value VARCHAR(255)  NOT NULL,
    provider       VARCHAR(20)   NOT NULL,
    detected_at    DATETIME(6)   NOT NULL,
    status         VARCHAR(20)   NOT NULL,              -- PENDING / APPLIED / REJECTED
    resolved_at    DATETIME(6)   NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT fk_event_source_changes_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 팬 제안. user_id 는 users 를 가리키지만 FK 를 두지 않는다: users 테이블은 account 기능(0010_account.sql)이 소유하며
-- 파일 적용 순서상 이 파일이 먼저 실행될 수 있고, 탈퇴(소프트 삭제) 후에도 제안은 남겨야 한다.
CREATE TABLE event_proposals (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id    BIGINT        NOT NULL,
    user_id     BIGINT        NULL,
    content     VARCHAR(2000) NOT NULL,
    status      VARCHAR(20)   NOT NULL,                 -- PENDING / APPLIED / HELD
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT fk_event_proposals_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
