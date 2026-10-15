-- photocard: 완성 이미지 한 장 + 메타(종류·배경색·프레임·스티커 배치 JSON)가 포토카드. 네컷(FOURCUT)도 기기에서 합성한 한 장으로 올라온다.
-- 기록(attendances)에 붙이면 그 별이 가장 밝아진다. 기록 하나에는 카드 한 장만(attendance_id 유일), 카드를 지우거나 기록이 지워지면 NULL 로.
-- 스티커 세트·스페셜 프레임은 배포 기간(available_from ~ available_until) 안에서만 공개 목록에 나온다. 이름 번역은 sticker_set_translations 에 필드·로케일당 한 행.
-- JSON 컬럼(stickers, slot_rects)은 내용으로 조회하지 않는다 (entity.md §3(h)).

-- 네컷 프레임. SVG 와 같은 이름의 .json(사진 칸 좌표)을 S3 에 함께 둔다. 이미 만든 네컷이 가리키므로 소프트 삭제
CREATE TABLE frames (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100) NOT NULL,             -- 평문(번역 없음)
    kind            VARCHAR(20)  NOT NULL,             -- ORIGINAL(상시) / SPECIAL(기간 한정)
    season          VARCHAR(50)  NULL,
    available_from  DATE         NULL,                 -- SPECIAL 만, ORIGINAL 은 NULL
    available_until DATE         NULL,                 -- SPECIAL 만(포함), ORIGINAL 은 NULL
    image_key       VARCHAR(255) NOT NULL,             -- frames/{uuid}.svg, 칸 좌표는 frames/{uuid}.json
    slot_rects      JSON         NOT NULL,             -- 사진 칸 [{x, y, w, h}] 3~4개, 1080×3240 좌표
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    deleted_at      DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE photocards (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    attendance_id    BIGINT       NULL,                 -- 연결한 기록(1:1). 카드 삭제·기록 삭제 시 NULL 로
    image_key        VARCHAR(255) NOT NULL,             -- photocards/{userId}/{uuid}.{ext}
    kind             VARCHAR(20)  NOT NULL,             -- CARD(4:5) / FOURCUT(1:3)
    background_color VARCHAR(7)   NULL,                 -- 캔버스 배경색 #RRGGBB
    frame_id         BIGINT       NULL,                 -- FOURCUT 에 쓴 프레임
    stickers         JSON         NOT NULL,             -- PlacedSticker 배열 [{stickerId, x, y, scale, rotation}], stickerId 는 숫자 문자열 또는 code:*
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    deleted_at       DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_photocards_attendance UNIQUE (attendance_id),
    CONSTRAINT fk_photocards_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_photocards_attendance FOREIGN KEY (attendance_id) REFERENCES attendances (id),
    CONSTRAINT fk_photocards_frame FOREIGN KEY (frame_id) REFERENCES frames (id)
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
    name           VARCHAR(100) NULL,                 -- 화면 읽기용 이름(세트 원어 로케일)
    category       VARCHAR(30)  NULL,                 -- 편집기 서랍의 종류(heart, star…)
    sticker_group  VARCHAR(30)  NULL,                 -- 서랍 안의 묶음(pattern, pearl…)
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_stickers_set FOREIGN KEY (sticker_set_id) REFERENCES sticker_sets (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
