-- catalog: 앨범·곡 마스터, 필드 단위 번역, setlist.fm 별칭
CREATE TABLE albums (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    title                VARCHAR(255) NOT NULL,
    original_locale      VARCHAR(10)  NOT NULL,
    release_date         DATE         NULL,
    cover_key            VARCHAR(255) NULL,
    itunes_collection_id BIGINT       NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 필드 하나·로케일 하나당 한 행. source = PROVIDER / MACHINE / ADMIN
CREATE TABLE album_translations (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    album_id   BIGINT       NOT NULL,
    field      VARCHAR(20)  NOT NULL,
    locale     VARCHAR(10)  NOT NULL,
    text       VARCHAR(255) NOT NULL,
    source     VARCHAR(20)  NOT NULL,
    reviewed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_album_translations_field_locale UNIQUE (album_id, field, locale),
    CONSTRAINT fk_album_translations_album FOREIGN KEY (album_id) REFERENCES albums (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE songs (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    album_id         BIGINT       NULL,
    title            VARCHAR(255) NOT NULL,
    original_locale  VARCHAR(10)  NOT NULL,
    itunes_track_id  BIGINT       NULL,
    youtube_video_id VARCHAR(20)  NULL,
    -- 새 선택(기록의 최고의 곡, 셋리 추가, 미매칭 연결)에 쓸 수 있는지. FALSE 는 기존 기록·셋리 표시용으로만 남긴다
    selectable       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_songs_album FOREIGN KEY (album_id) REFERENCES albums (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE song_translations (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    song_id    BIGINT       NOT NULL,
    field      VARCHAR(20)  NOT NULL,
    locale     VARCHAR(10)  NOT NULL,
    text       VARCHAR(255) NOT NULL,
    source     VARCHAR(20)  NOT NULL,
    reviewed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_song_translations_field_locale UNIQUE (song_id, field, locale),
    CONSTRAINT fk_song_translations_song FOREIGN KEY (song_id) REFERENCES songs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- setlist.fm 표기. 저장 전 AliasNormalizer 로 정규화하므로 전체 곡에서 유일하다
CREATE TABLE song_aliases (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    song_id    BIGINT       NOT NULL,
    alias      VARCHAR(255) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_song_aliases_alias UNIQUE (alias),
    CONSTRAINT fk_song_aliases_song FOREIGN KEY (song_id) REFERENCES songs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
