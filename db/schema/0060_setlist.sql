-- setlist: 행사당 셋리 위키 페이지, 현재 항목, 리비전 역사, 미매칭 setlist.fm 곡명, YouTube 채널 자격 증명
CREATE TABLE setlist_pages (
    event_id            BIGINT       NOT NULL,
    order_confirmed     BOOL         NOT NULL DEFAULT FALSE,   -- 관리자 토글. merged_at 과 독립
    merged_at           DATETIME(6)  NULL,                     -- 마지막 setlist.fm 병합 시각 (병합 잡이 기록)
    setlistfm_version   VARCHAR(64)  NULL,                     -- 마지막으로 병합한 setlist.fm versionId
    youtube_playlist_id VARCHAR(64)  NULL,
    version             INT          NOT NULL DEFAULT 0,       -- 리비전마다 +1. 편집 요청의 낙관적 잠금 값
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_setlist_pages_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE setlist_items (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    event_id     BIGINT      NOT NULL,
    song_id      BIGINT      NOT NULL,
    position     INT         NOT NULL,
    in_setlistfm BOOL        NOT NULL,                         -- false = 위키에만 있는 곡 ("setlist.fm 에 없음" 표시)
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_setlist_items_event_song UNIQUE (event_id, song_id),
    CONSTRAINT fk_setlist_items_page FOREIGN KEY (event_id) REFERENCES setlist_pages (event_id),
    CONSTRAINT fk_setlist_items_song FOREIGN KEY (song_id) REFERENCES songs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- editor_user_id 는 편집 횟수 제한·남용 대응용 내부 컬럼. 어떤 API 응답에도 나가지 않고 탈퇴 시 NULL 이 된다
CREATE TABLE setlist_revisions (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    event_id       BIGINT      NOT NULL,
    version        INT         NOT NULL,
    editor_user_id BIGINT      NULL,
    source         VARCHAR(20) NOT NULL,                       -- FAN / SETLISTFM_SEED / SETLISTFM_MERGE / REVERT
    snapshot       JSON        NOT NULL,                       -- [{songId, position, inSetlistFm}]
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_setlist_revisions_event_version UNIQUE (event_id, version),
    CONSTRAINT fk_setlist_revisions_page FOREIGN KEY (event_id) REFERENCES setlist_pages (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE setlist_unmatched_names (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    event_id   BIGINT       NOT NULL,
    raw_name   VARCHAR(255) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_setlist_unmatched_names_event_name UNIQUE (event_id, raw_name),
    CONSTRAINT fk_setlist_unmatched_names_page FOREIGN KEY (event_id) REFERENCES setlist_pages (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 잔스페이스 유튜브 채널의 OAuth 리프레시 토큰. 단일 행(id = 1). 재기동 후에도 남아야 하므로 Redis 가 아니라 DB
CREATE TABLE youtube_credentials (
    id            TINYINT      NOT NULL,
    refresh_token VARCHAR(512) NOT NULL,
    channel_id    VARCHAR(64)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
