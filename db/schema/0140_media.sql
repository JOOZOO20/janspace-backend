-- media: 서버 측 이미지 변환(JAN-05). 원본 1개 = asset 1행, 화면별 rendition = 3 profile × 2 format = 6행.
-- 원본이 저장되는 트랜잭션에서 asset·rendition 행과 rendition 마다 Outbox 변환 메시지(jan-media-transcode)를 함께 만든다.
-- Worker 는 rendition 을 조건부 UPDATE 로 claim 하고(Lease 90초, 30초마다 연장), 자기 Lease 일 때만 READY 로 바꾼다. 시각 비교는 NOW(6).
CREATE TABLE media_assets (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_type      VARCHAR(20)  NOT NULL,             -- EVENT_POSTER / ALBUM / PHOTOCARD / FOURCUT
    owner_id        BIGINT       NOT NULL,
    original_key    VARCHAR(255) NOT NULL,             -- 원본 S3 key. 서빙은 소유자의 현재 key 와 같은 asset 만 쓴다
    original_sha256 CHAR(64)     NOT NULL,
    width           INT          NOT NULL,             -- EXIF 회전 후 화면 기준. 입구 검증 실패 원본은 0
    height          INT          NOT NULL,
    mime_type       VARCHAR(50)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,             -- PENDING / READY / PARTIAL / FAILED (rendition 이 모두 끝나면 집계)
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uk_owner_sha UNIQUE (owner_type, owner_id, original_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE media_renditions (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    asset_id         BIGINT        NOT NULL,
    profile          VARCHAR(20)   NOT NULL,           -- thumb / list / detail
    format           VARCHAR(10)   NOT NULL,           -- webp / jpeg / png (투명 PNG 원본은 jpeg 대신 png)
    output_key       VARCHAR(255)  NULL,               -- media/{assetId}/{profile}-{출력 sha256 앞 8자}.{ext}
    width            INT           NULL,
    height           INT           NULL,
    bytes            BIGINT        NULL,
    status           VARCHAR(20)   NOT NULL,           -- PENDING / PROCESSING / READY / FAILED
    attempt          INT           NOT NULL DEFAULT 0, -- claim 횟수
    lease_owner      VARCHAR(100)  NULL,
    lease_expires_at DATETIME(6)   NULL,
    last_error       VARCHAR(1000) NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT uk_asset_profile_format UNIQUE (asset_id, profile, format),
    KEY idx_status_lease (status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
