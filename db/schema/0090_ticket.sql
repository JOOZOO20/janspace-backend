-- ticket: 실물 티켓을 기기에서 읽어 티켓북에 꽂는 픽셀 티켓. 사진과 인식 글자는 서버에 저장하지 않는다.
-- 공연명·공연장·시각은 행사(events) 값을 쓰므로 티켓에는 사용자가 확인한 좌석과 색만 둔다. 기록 연결은 컬럼 없이 같은 사용자·같은 행사의 기록으로 조회한다.
-- 티켓 스킨은 스티커 세트와 같은 운영 방식이다: 관리자가 시즌마다 등록하고 배포 기간(available_from ~ available_until, NULL = 열림) 안에서만 공개 목록에 나온다.
-- 색은 모두 #rrggbb 소문자 7자.
CREATE TABLE ticket_skins (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100) NOT NULL,             -- 평문 (번역 없음)
    season          VARCHAR(50)  NULL,
    paper_color     VARCHAR(7)   NOT NULL,             -- 종이색
    accent_color    VARCHAR(7)   NOT NULL,             -- 포인트 색
    ink_color       VARCHAR(7)   NOT NULL,             -- 글자색
    available_from  DATE         NULL,                 -- NULL = 시작 제한 없음
    available_until DATE         NULL,                 -- NULL = 상시
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE, -- 시즌 스킨이 없을 때 쓰는 기본. 애플리케이션 규칙: 최대 1개, 삭제 불가
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 사용자 x 행사 한 장. 사흘 연속 공연은 날짜마다 행사가 달라 티켓도 날마다 한 장이다.
-- 행사 FK 는 RESTRICT: 티켓이 꽂힌 행사는 물리 삭제할 수 없다. 스킨을 지우면 skin_id 는 NULL(= 기본 스킨)로 돌아간다.
CREATE TABLE tickets (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    event_id     BIGINT       NOT NULL,
    seat         VARCHAR(100) NULL,                    -- 사용자가 확인한 좌석 문자열 (예: 1층 A구역 12열 5번)
    accent_color VARCHAR(7)   NULL,                    -- 기기가 사진에서 뽑은 대표색. 스킨이 지정되면 스킨 색이 우선
    skin_id      BIGINT       NULL,                    -- NULL = 기본 스킨
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tickets_user_event UNIQUE (user_id, event_id),
    CONSTRAINT fk_tickets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_tickets_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tickets_skin FOREIGN KEY (skin_id) REFERENCES ticket_skins (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
