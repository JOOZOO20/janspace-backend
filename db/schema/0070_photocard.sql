-- photocard: 유저 사진 + 스티커 배치(stickers JSON) 한 장이 포토카드. 기록(attendances)에 붙이면 그 별이 가장 밝아진다.
-- 스티커 세트는 배포 기간(available_from ~ available_until, NULL = 열림) 안에서만 공개 목록에 나온다. 이름 번역은 sticker_set_translations 에 필드·로케일당 한 행.
-- JSON 컬럼(stickers)은 내용으로 조회하지 않는다 (entity.md §3(h)).
CREATE TABLE photocards (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    attendance_id BIGINT       NULL,                 -- 연결한 기록. 기록 삭제 시 NULL 로
    image_key     VARCHAR(255) NOT NULL,             -- photocards/{userId}/{uuid}.{ext}
    stickers      JSON         NOT NULL,             -- PlacedSticker 배열 [{stickerId, x, y, scale, rotation}]
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    deleted_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_photocards_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_photocards_attendance FOREIGN KEY (attendance_id) REFERENCES attendances (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sticker_sets (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100) NOT NULL,
    original_locale VARCHAR(10)  NOT NULL,
    season          VARCHAR(50)  NULL,
    available_from  DATE         NULL,               -- NULL = 시작 제한 없음
    available_until DATE         NULL,               -- NULL = 상시
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 필드 하나·로케일 하나당 한 행. field = NAME, source = PROVIDER / MACHINE / ADMIN
CREATE TABLE sticker_set_translations (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    sticker_set_id BIGINT       NOT NULL,
    field          VARCHAR(20)  NOT NULL,
    locale         VARCHAR(10)  NOT NULL,
    text           VARCHAR(100) NOT NULL,
    source         VARCHAR(20)  NOT NULL,
    reviewed       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sticker_set_translations_field_locale UNIQUE (sticker_set_id, field, locale),
    CONSTRAINT fk_sticker_set_translations_set FOREIGN KEY (sticker_set_id) REFERENCES sticker_sets (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stickers (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    sticker_set_id BIGINT       NOT NULL,
    image_key      VARCHAR(255) NOT NULL,             -- stickers/{setId}/{uuid}.{ext}
    sort_order     INT          NOT NULL,             -- 업로드 순서
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_stickers_set FOREIGN KEY (sticker_set_id) REFERENCES sticker_sets (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
