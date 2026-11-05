-- audit: 관리자 조치 감사 로그. 조치와 같은 트랜잭션에서 한 행씩 쓰고 수정·삭제하지 않는다(append-only).
-- 운영 DB 에서는 애플리케이션 계정의 이 테이블 UPDATE·DELETE 권한을 회수하는 것을 권장한다.
CREATE TABLE admin_audit_logs (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_type  VARCHAR(20)   NOT NULL,   -- ADMIN / MCP_AGENT / SYSTEM
    actor_id    VARCHAR(100)  NOT NULL,   -- ADMIN 이면 관리자 사용자 ID
    action      VARCHAR(50)   NOT NULL,   -- PROPOSAL_APPROVE / FIELD_LOCK_SET ...
    target_type VARCHAR(50)   NOT NULL,   -- SOURCE_CHANGE / EVENT / VENUE ...
    target_id   VARCHAR(100)  NOT NULL,
    before_json JSON          NULL,       -- 바뀐 필드의 이전 값만. 개인정보(이메일·토큰·사용자 ID) 금지
    after_json  JSON          NULL,       -- 바뀐 필드의 이후 값만
    reason      VARCHAR(500)  NULL,
    request_id  VARCHAR(64)   NULL,       -- X-Request-Id (로그 상관관계)
    created_at  DATETIME(6)   NOT NULL,
    KEY idx_target (target_type, target_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
