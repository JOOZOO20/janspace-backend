-- agent: MCP 진단 서버(jan-mcp-server)가 메인 앱 관리자 조회 API 를 부르는 서비스 계정과, AI 가 만든 조치 요청(사람 승인 대기열).

-- 서비스 계정. API key 원문은 저장하지 않고 SHA-256 hex 만 둔다. ADMIN 권한은 줄 수 없고 roles 는 MCP_READER / MCP_REQUESTER 만 쓴다.
-- 발급: 무작위 key 를 만든 뒤 INSERT INTO service_accounts (name, key_hash, roles, created_at, updated_at)
--       VALUES ('jan-mcp', SHA2('<key>', 256), '["MCP_READER","MCP_REQUESTER"]', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
CREATE TABLE service_accounts (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50)   NOT NULL,
    key_hash    CHAR(64)      NOT NULL,   -- API key 의 SHA-256 소문자 hex
    roles       JSON          NOT NULL,   -- ["MCP_READER","MCP_REQUESTER"]
    disabled_at DATETIME(6)   NULL,       -- 값이 있으면 인증 거부 (key 회수)
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT uk_service_accounts_name UNIQUE (name),
    CONSTRAINT uk_service_accounts_key_hash UNIQUE (key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- MCP 조치 요청. request_action Tool 은 이 행만 만들고, 관리자가 승인하면 기존 실행 경로로 처리한다.
CREATE TABLE agent_action_requests (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type          VARCHAR(40)   NOT NULL,   -- RETRY_PLAYLIST_SYNC / REDRIVE_DEAD_LETTER / LINK_UNMATCHED_SONG / RETRY_MEDIA_RENDITION
    target_ref    VARCHAR(100)  NOT NULL,   -- 조치 대상 ID (RETRY_PLAYLIST_SYNC = 행사 ID, LINK_UNMATCHED_SONG = 미매칭 ID)
    payload_json  JSON          NULL,       -- 실행에 필요한 추가 값 (LINK_UNMATCHED_SONG 의 songId)
    reason        VARCHAR(1000) NOT NULL,
    evidence_json JSON          NOT NULL,   -- 근거 ID 목록 (run ID, snapshot ID 등)
    status        VARCHAR(20)   NOT NULL,   -- PENDING / APPROVED / REJECTED / EXECUTED / FAILED
    requested_by  VARCHAR(100)  NOT NULL,   -- 서비스 계정 이름 + MCP 클라이언트
    decided_by    VARCHAR(100)  NULL,       -- 승인·거절한 관리자 사용자 ID
    decided_at    DATETIME(6)   NULL,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    -- PENDING 일 때만 값이 생겨 같은 대상의 PENDING 중복을 막는다 (NULL 은 UNIQUE 가 여러 개 허용)
    pending_key   VARCHAR(150)  GENERATED ALWAYS AS
                  (CASE WHEN status = 'PENDING' THEN CONCAT(type, ':', target_ref) END) STORED,
    CONSTRAINT uk_agent_action_requests_pending UNIQUE (pending_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
