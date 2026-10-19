# Stagelog Backend

한 아티스트의 팬이 공연을 다녀올 때마다 3분 기록으로 그날을 별 하나로 남기고, 다닌 거리를 합쳐 얼마나 높이 올라갔는지 보는 웹 서비스 **Stagelog**의 백엔드입니다. 대상 아티스트는 코드가 아니라 설정값(setlist.fm MBID, KOPIS·Ticketmaster 검색어, 네이버 캘린더 키)으로 정하며, 테스트와 API 예시의 데이터는 가상 밴드 "스타더스트 클럽(Stardust Club)"입니다.

- Java 21, Spring Boot 4, MySQL 8, Redis, AWS S3
- 기능: 소셜 로그인(PKCE 일회용 코드 교환), 행사(네이버 캘린더·KOPIS·Ticketmaster·setlist.fm 동기화와 관리자 확인 큐), 곡·앨범 마스터, 셋리 위키(setlist.fm 병합·유튜브 재생목록), 기록·거리·높이·별자리, 포토카드, 웹 푸시

## 구조

```
com.stagelog
├─ common/        응답 봉투, 예외, 엔티티 기반, 로케일
├─ bootstrap/     보안(JWT)·JPA·스케줄링·Swagger 설정
├─ account/ event/ catalog/ setlist/ attendance/ photocard/ notification/ ops/
│   └─ api / application / domain / infrastructure
└─ integration/   제공자별 HTTP 클라이언트 (naver, kopis, ticketmaster, setlistfm, youtube, itunes, kakao, locationiq, deepl, s3, webpush, discord)
```

기능 사이의 의존은 `application` 공개 계약과 도메인 이벤트로만 이어지며, `ArchitectureTest`(ArchUnit)가 경계를 검사합니다.

## 컨벤션 요약

- 모든 REST 응답은 봉투 `ResponseDTO {code, data, message}`로 감쌉니다. 성공과 실패가 같은 모양입니다.
- 경로는 `/api/v1`로 시작하고, 관리자 API는 `/api/v1/admin/**`에 둡니다.
- 실패는 `ErrorCode` 열거형(SCREAMING_SNAKE_CASE, 예: `INVALID_INPUT`, `CONSENT_REQUIRED`)으로 나타내며, 그 이름이 응답의 `code`가 됩니다.
- 기능 패키지는 `api / application / domain / infrastructure` 계층으로 나눕니다. `domain`은 바깥 계층을, `application`은 `api`·`infrastructure`·`integration`을 참조하지 않습니다.
- 기능 사이의 의존은 상대 기능의 `application` 공개 계약(Query·UseCase·Snapshot)과 도메인 이벤트로만 이어집니다. 다른 기능의 엔티티·리포지토리를 직접 쓰지 않습니다.
- 외부 HTTP 클라이언트는 `integration/`에 두고 기능을 모르게 합니다. 기능은 `application/port` 인터페이스로 부르고, `infrastructure` 어댑터가 그 포트를 구현합니다.
- 위 경계는 `ArchitectureTest`(ArchUnit)가 `./gradlew test`에서 검사합니다.

## 로컬 실행

```bash
cp .env.example .env            # 값 채우기 (비어 있어도 부팅은 됨)
docker compose up -d            # MySQL 8.4 + Redis. db/schema/*.sql 이 초기화 시 자동 적용
./gradlew bootRun --args='--spring.profiles.active=local'
```

- Swagger: http://localhost:8080/swagger-ui.html
- 첫 관리자 지정: 로그인 후 `UPDATE users SET is_admin = TRUE WHERE id = ?;`
- DB 초기화: `docker compose down -v && docker compose up -d`

## 스키마 관리

마이그레이션 도구를 쓰지 않습니다. 새 DB는 `db/schema/*.sql`을 사전순으로 적용해 만들고(로컬·테스트는 MySQL 이미지 initdb), 운영 DB 변경은 `db/changes/<릴리스>/`의 SQL을 mysql 클라이언트로 적용한 뒤 검증하고 `schema_history`에 기록합니다. 애플리케이션은 모든 환경에서 `ddl-auto=validate`만 사용합니다.

## 테스트

```bash
./gradlew test              # 단위 + ArchUnit
./gradlew integrationTest   # Testcontainers(MySQL·Redis). Docker 필요
./gradlew check             # 둘 다
```

외부 API는 기록된 응답 픽스처(`src/test/resources/fixtures`)로 검증합니다. 실서비스 호출은 키를 넣은 뒤 확인합니다.

## 환경 변수

`.env.example`에 이름과 설명이 있습니다. 대상 아티스트를 정하는 값은 `SETLISTFM_ARTIST_MBID`, `KOPIS_ARTIST_KEYWORD`, `TICKETMASTER_KEYWORD`, `NAVER_CALENDAR_PUBLISHED_KEY`, `YOUTUBE_PLAYLIST_TITLE_PREFIX`이며, 비어 있으면 해당 동기화는 건너뜁니다(재생목록 앞머리는 생략). 비밀값은 저장소에 두지 않고, S3는 AWS SDK 기본 자격 증명 체인(EC2 IAM 역할)을 씁니다.
