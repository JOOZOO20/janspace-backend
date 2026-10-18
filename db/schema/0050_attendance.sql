-- attendance: 갈래요(planned_at) -> 갔어요(attended_at) -> 기록(emotion_1) 이 한 행에서 진행한다.
-- unique(user_id, event_id) 는 소프트 삭제 행을 포함하므로 삭제 후 다시 만들 때는 기존 행을 되살린다.
-- 거리(distance_km)는 사용자 체인 재계산 결과의 캐시이고, 누적 거리·높이는 저장하지 않는다.
CREATE TABLE attendances (
    id                      BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id                 BIGINT        NOT NULL,
    event_id                BIGINT        NOT NULL,
    planned_at              DATETIME(6)   NULL,                 -- 갈래요 누른 순간
    attended_at             DATETIME(6)   NULL,                 -- 갔어요·기록 만든 순간 (별 1개)
    emotion_1               VARCHAR(20)   NULL,                 -- Emotion 코드, 순서 = 순위. 소급 기록은 셋 다 NULL
    emotion_2               VARCHAR(20)   NULL,
    emotion_3               VARCHAR(20)   NULL,
    best_song_id            BIGINT        NULL,
    memo                    VARCHAR(500)  NULL,
    departure_name          VARCHAR(100)  NULL,                 -- 기록 시점 출발지 스냅샷
    departure_country_code  VARCHAR(2)    NULL,
    departure_lat           DECIMAL(9,6)  NULL,
    departure_lng           DECIMAL(9,6)  NULL,
    continued_from_previous BOOL          NOT NULL DEFAULT FALSE,  -- 연속 이동 토글
    distance_km             DECIMAL(8,1)  NULL,                 -- 좌표가 없으면 NULL
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    deleted_at              DATETIME(6)   NULL,
    CONSTRAINT uk_attendances_user_event UNIQUE (user_id, event_id),
    CONSTRAINT fk_attendances_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_attendances_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_attendances_song FOREIGN KEY (best_song_id) REFERENCES songs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
