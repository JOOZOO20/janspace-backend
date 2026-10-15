-- account: 사용자와 소셜 로그인 수단. 인증 코드·리프레시 토큰은 Redis 에 두므로 테이블이 없다.
CREATE TABLE users (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    nickname               VARCHAR(50)   NOT NULL,
    -- 같은 이메일의 네이버·구글 계정이 서로 다른 사용자로 존재할 수 있어 유니크를 두지 않는다
    email                  VARCHAR(255)  NULL,
    locale                 VARCHAR(10)   NOT NULL DEFAULT 'ko',
    is_admin               BOOL          NOT NULL DEFAULT FALSE,
    departure_name         VARCHAR(100)  NULL,
    departure_lat          DECIMAL(9,6)  NULL,
    departure_lng          DECIMAL(9,6)  NULL,
    departure_country_code VARCHAR(2)    NULL,
    consented_at           DATETIME(6)   NULL,
    terms_version          VARCHAR(20)   NULL,
    -- 프로필 사진 S3 키 avatars/{userId}/{uuid}.{ext}. NULL 이면 클라이언트가 기본 그림(픽셀 원숭이)을 그린다
    avatar_key             VARCHAR(255)  NULL,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,
    deleted_at             DATETIME(6)   NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_social_accounts (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    user_id          BIGINT        NOT NULL,
    provider         VARCHAR(20)   NOT NULL,
    provider_user_id VARCHAR(255)  NOT NULL,
    email            VARCHAR(255)  NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_social_accounts_provider_user (provider, provider_user_id),
    CONSTRAINT fk_user_social_accounts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
