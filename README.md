# Stagelog Backend

한 아티스트의 팬이 공연을 다녀올 때마다 3분 기록으로 그날을 별 하나로 남기고, 다닌 거리를 합쳐 얼마나 높이 올라갔는지 보는 웹 서비스 **Stagelog**의 백엔드입니다. 대상 아티스트는 코드가 아니라 설정값(setlist.fm MBID, KOPIS·Ticketmaster 검색어, 네이버 캘린더 키)으로 정하며, 테스트와 API 예시의 데이터는 가상 밴드 "스타더스트 클럽(Stardust Club)"입니다.

- Java 21, Spring Boot 4, MySQL 8, Redis, AWS S3
- 기능: 소셜 로그인(PKCE 일회용 코드 교환), 행사(네이버 캘린더·KOPIS·Ticketmaster·setlist.fm 동기화와 관리자 확인 큐), 곡·앨범 마스터, 셋리 위키(setlist.fm 병합·유튜브 재생목록), 기록·거리·높이·별자리, 포토카드, 웹 푸시

## 구조

```
com.stagelog
├─ common/        응답 봉투, 예외, 엔티티 기반, 로케일
├─ bootstrap/     보안(JWT)·JPA·스케줄링·Swagger 설정
├─ account/ event/ catalog/ setlist/ attendance/ photocard/ notification/ ticket/ ops/
│   └─ api / application / domain / infrastructure
└─ integration/   제공자별 HTTP 클라이언트 (naver, kopis, ticketmaster, setlistfm, youtube, itunes, kakao, locationiq, deepl, s3, webpush, discord)
```

기능 사이의 의존은 `application` 공개 계약과 도메인 이벤트로만 이어지며, `ArchitectureTest`(ArchUnit)가 경계를 검사합니다. 규칙은 아래 [컨벤션 요약](#컨벤션-요약)에 있습니다.

## 여기부터 보세요

1. **기능 경계와 ArchUnit** — [`ArchitectureTest`](src/test/java/com/stagelog/ArchitectureTest.java)
   - 규칙 7개: 계층 방향, 다른 기능의 `api`·`domain`·`infrastructure` 참조 금지, `integration`은 기능을 모름, 기능 간 순환 금지. 패키지 경계를 문서가 아니라 빌드가 지킵니다.
2. **출처 접기와 관리자 확인 큐** — [`EventSourcePolicy`](src/main/java/com/stagelog/event/domain/EventSourcePolicy.java), [`ApplySourcesUseCase`](src/main/java/com/stagelog/event/application/ApplySourcesUseCase.java)
   - 제공자 4곳이 따로 도는데 "마지막에 온 값"이 아니라 필드별 고정 우선순위로 매번 전체를 다시 접어, 결과가 실행 순서와 무관합니다. 날짜·시작 시각·공연장의 값 → 다른 값 변경만 사람이 확인합니다. 정책은 Spring을 모르는 순수 클래스입니다.
3. **셋리 위키 쓰기 경로와 잠금** — [`SetlistWriter`](src/main/java/com/stagelog/setlist/application/SetlistWriter.java), [`EditSetlistUseCase`](src/main/java/com/stagelog/setlist/application/EditSetlistUseCase.java)
   - 편집·되돌리기·병합·시드가 모두 한 쓰기 단계를 지나며 페이지 행 잠금으로 줄 섭니다. 없는 행에 `FOR UPDATE`를 먼저 걸면 gap 잠금끼리 교착하므로 `INSERT … ON DUPLICATE KEY UPDATE`로 만든 뒤 잠급니다. 편집은 버전 불일치면 409(낙관적 잠금)입니다.
4. **거리 체인 재계산과 도메인 이벤트** — [`DistanceRecalculation`](src/main/java/com/stagelog/attendance/application/DistanceRecalculation.java), [`AttendanceEventListeners`](src/main/java/com/stagelog/attendance/infrastructure/AttendanceEventListeners.java)
   - 연속 이동 거리는 앞 기록에 의존해 부분 갱신 없이 사용자 체인 전체를 다시 계산합니다. 행사·공연장 변경은 커밋 뒤 이벤트로 받아 새 트랜잭션에서 처리합니다.
5. **푸시 디스패처의 상태 전이** — [`DispatchDuePushJobs`](src/main/java/com/stagelog/notification/application/DispatchDuePushJobs.java), [`PushDelivery`](src/main/java/com/stagelog/notification/domain/PushDelivery.java)
   - 잡 선점 → 물질화(배달 행을 한 번만 생성) → 배달 선점·전송 → 고아 `SENDING` 회수. 선점은 조건부 UPDATE, 외부 전송은 트랜잭션 밖입니다. 스케줄 잡 중 유일하게 인스턴스가 여럿이어도 안전하도록 만든 곳입니다.

## 핵심 설계 결정

각 결정은 **선택 / 대안 / 대가** 순입니다.

**1. OAuth 로그인을 직접 구현 (PKCE 결합 일회용 코드)** — [`SocialLoginService`](src/main/java/com/stagelog/account/application/SocialLoginService.java), [`Pkce`](src/main/java/com/stagelog/account/application/Pkce.java)
- 선택: 인가 URL·토큰 교환·프로필 조회를 `RestClient`로 직접 호출합니다. 프론트가 보낸 PKCE challenge를 OAuth `state`와 함께 저장했다가, 콜백에서 토큰 대신 그 challenge에 묶인 일회용 코드를 내주고 `POST /auth/token`에서 verifier로 교환합니다.
- 대안: Spring Security `oauth2-client`. challenge 결합과 콜백 이후 일회용 코드 발급을 직접 통제하려고 쓰지 않았습니다.
- 대가: state·일회용 코드·리프레시 토큰 저장소(Redis)와 제공자 어댑터를 직접 유지하고 테스트해야 합니다.

**2. 마이그레이션 도구 없이 SQL 파일로 스키마 관리** — [`db/schema`](db/schema), [`SchemaIntegrationTest`](src/test/java/com/stagelog/support/SchemaIntegrationTest.java)
- 선택: 새 DB는 MySQL 이미지 initdb가 `db/schema/*.sql`을 그대로 실행해 만들고, 운영 변경은 사람이 적용·검증한 뒤 `schema_history`에 기록합니다. 앱은 모든 환경에서 `ddl-auto=validate`만 쓰고 스키마를 바꾸지 않습니다.
- 대안: Flyway·Liquibase(앱이 기동하며 적용).
- 대가: 운영 적용이 수동 절차입니다. MySQL DDL은 문장마다 암묵 커밋이라 부분 적용 복구도 절차로 합니다. 업그레이드 검증 테스트(B)는 첫 릴리스 전이라 baseline이 없어 지금은 건너뜁니다.

**3. 좌표 → 시간대를 오프라인 데이터로** — [`TimeshapeTimezoneAdapter`](src/main/java/com/stagelog/event/infrastructure/TimeshapeTimezoneAdapter.java)
- 선택: timeshape(경계 데이터 `2025b`). 제공자가 IANA 시간대를 주면(Ticketmaster) 조회를 건너뜁니다.
- 대안: Google Time Zone 같은 외부 API. 조회 지점이 동기화 트랜잭션 안이라 네트워크·키·과금·레이트 리밋을 끼우지 않으려 했고, 같은 좌표에 항상 같은 답이라 테스트가 결정적입니다.
- 대가: jar 약 24MB, 첫 조회 때 수 초와 수백 MB 힙(그래서 지연 초기화, 단위 테스트 힙 2g). 경계 갱신은 의존성 버전 업으로만 받습니다.

**4. 기능 간 연결은 도메인 이벤트 (AFTER_COMMIT, at-most-once + 보정)** — [`AttendanceEventListeners`](src/main/java/com/stagelog/attendance/infrastructure/AttendanceEventListeners.java), [`PushEventListeners`](src/main/java/com/stagelog/notification/infrastructure/PushEventListeners.java)
- 선택: 리스너는 커밋 뒤 `REQUIRES_NEW` 트랜잭션에서 처리하고, 실패하면 그 처리만 롤백하고 ERROR 로그를 남깁니다. 발행한 요청은 이미 커밋돼 실패를 돌려받지 않습니다.
- 대안: 이벤트를 테이블에 저장했다가 재전송하는 트랜잭셔널 아웃박스.
- 대가: 실패한 처리는 자동 재시도되지 않고 보정이 경로마다 다릅니다. 거리는 그 사용자의 다음 기록 쓰기가 체인 전체를 다시 계산하고, 푸시 예약은 [`DispatchScheduler`](src/main/java/com/stagelog/notification/infrastructure/DispatchScheduler.java)가 하루 1회 재예약하며, 나머지는 로그의 ID로 수동 재처리합니다.

**5. 웹 푸시는 라이브러리로 요청만 만들고 전송은 `RestClient`로** — [`WebPushClient`](src/main/java/com/stagelog/integration/webpush/WebPushClient.java), [`build.gradle.kts`](build.gradle.kts)
- 선택: `web-push` 라이브러리는 aes128gcm 암호화와 VAPID 서명(요청 생성)에만 쓰고, 라이브러리의 Apache 비동기 HTTP 클라이언트는 의존성에서 뺐습니다. 응답 상태는 판정하지 않고 notification에 넘겨 재시도 여부를 정하게 합니다.
- 대안: 라이브러리의 전송부(`PushService`)를 그대로 사용.
- 대가: 다른 외부 호출과 같은 `RestClient`·테스트 방식을 쓰는 대신, 라이브러리의 요청 생성 API(`AbstractPushService.prepareRequest`)에 직접 묶입니다.

**6. 셋리 리비전은 항목 전체의 JSON 스냅샷** — [`SnapshotCodec`](src/main/java/com/stagelog/setlist/domain/SnapshotCodec.java), [`RevertSetlistUseCase`](src/main/java/com/stagelog/setlist/application/RevertSetlistUseCase.java)
- 선택: 쓰기마다 `setlist_revisions.snapshot`(JSON)에 항목 전체(`songId, position, inSetlistFm`)를 남깁니다. 되돌리기는 스냅샷으로 항목을 교체하고 `REVERT` 리비전을 하나 더 남깁니다.
- 대안: 변경분(diff) 저장, 또는 항목 단위 이력 테이블.
- 대가: 리비전마다 목록 전체를 저장하고, JSON 안의 곡 ID에는 외래 키 제약이 걸리지 않습니다.

**7. 단일 인스턴스 전제와 잠금 3종** — [`SyncRunner`](src/main/java/com/stagelog/event/application/sync/SyncRunner.java), [`RedisPlaylistSyncLock`](src/main/java/com/stagelog/setlist/infrastructure/RedisPlaylistSyncLock.java)
- 선택: `@Scheduled` 잡은 인스턴스 하나를 전제로 분산 락을 두지 않습니다. 정합성은 잠금으로 지킵니다: 같은 행사·사용자의 쓰기는 **행 잠금**(`SELECT … FOR UPDATE`), 외부 호출이 긴 재생목록 동기화는 **Redis `SET NX PX`**, 푸시 잡·배달 선점은 **조건부 UPDATE**.
- 대안: 모든 잡에 분산 락, 또는 `sync_runs` 조건부 선점.
- 대가: 인스턴스를 늘리면 잡이 겹쳐 돌아 외부 호출이 늘고, 동기화 중복 가드는 무력해집니다. 셋리 결과(리비전·알림)는 행 잠금과 결정적 병합 덕분에 중복되지 않습니다([`SetlistScheduler`](src/main/java/com/stagelog/setlist/infrastructure/scheduling/SetlistScheduler.java)).

## 테스트

```bash
./gradlew test              # 단위 + ArchUnit
./gradlew integrationTest   # Testcontainers(MySQL·Redis). Docker 필요
./gradlew check             # 둘 다
```

| 종류 | 무엇을 | 위치 |
| --- | --- | --- |
| 단위 | 순수 정책·계산(출처 접기, 거리 체인, PKCE 등), HTTP 클라이언트(기록된 응답 + `MockRestServiceServer`) | `./gradlew test` |
| 통합 | `db/schema`로 초기화한 MySQL·Redis 컨테이너 위의 API·유스케이스·스키마 검증 | [`IntegrationTest`](src/test/java/com/stagelog/support/IntegrationTest.java) 상속, `@Tag("integration")` |
| ArchUnit | 패키지 경계 규칙 7개 | [`ArchitectureTest`](src/test/java/com/stagelog/ArchitectureTest.java) |
| 픽스처 | 외부 API 응답 기록(ID는 가짜 값) | [`src/test/resources/fixtures`](src/test/resources/fixtures) |

- 2026-10-19 `./gradlew test integrationTest` 실행 결과: 단위 292개(64클래스, ArchUnit 포함) 실패 0, 통합 223개(39클래스) 실패 0·건너뜀 1(위 업그레이드 검증 B).
- 동시성 테스트: 셋리 동시 편집·첫 편집 경합·병합 중 커밋된 편집 보존([`SetlistConcurrencyIntegrationTest`](src/test/java/com/stagelog/setlist/SetlistConcurrencyIntegrationTest.java)), 디스패처 두 사이클 동시 실행 시 중복 발송 없음([`DispatchDuePushJobsIntegrationTest`](src/test/java/com/stagelog/notification/application/DispatchDuePushJobsIntegrationTest.java)), 같은 사용자 재계산 직렬화([`DistanceRecalculationIntegrationTest`](src/test/java/com/stagelog/attendance/application/DistanceRecalculationIntegrationTest.java)), 동시 기록 생성이 500 대신 409([`AttendanceApiIntegrationTest`](src/test/java/com/stagelog/attendance/api/AttendanceApiIntegrationTest.java)).
- 실서비스 호출은 자동 테스트에 넣지 않고, 키를 넣은 로컬 서버에서 확인합니다.

## 알려진 한계

코드 주석(`ponytail:`·`한계:`)에 적어 둔 것만 모았습니다.

- **다중 인스턴스 미대응.** 동기화 중복 실행 가드는 JVM 안 `AtomicReference`입니다([`SyncRunner`](src/main/java/com/stagelog/event/application/sync/SyncRunner.java)). setlist.fm 호출 간격도 인스턴스 하나 기준입니다([`SetlistFmClient`](src/main/java/com/stagelog/integration/setlistfm/SetlistFmClient.java)).
- **동기화 재시도·백오프 없음.** 제공자 호출이 실패하면 실행을 `FAILED`로 기록하고 운영자 알림을 보낸 뒤 끝나며, 다음 스케줄 실행을 기다립니다([`SyncRunner`](src/main/java/com/stagelog/event/application/sync/SyncRunner.java)). 백오프는 웹 푸시 배달에만 있습니다([`BackoffPolicy`](src/main/java/com/stagelog/notification/domain/BackoffPolicy.java)).
- **리프레시 토큰 재사용 감지가 원자적이지 않음.** 조회 → 사용 표시 사이에 동시 갱신 두 건이 모두 통과할 수 있습니다([`SocialLoginService`](src/main/java/com/stagelog/account/application/SocialLoginService.java)).
- **한도의 소폭 초과 허용.** 포토카드 월 한도([`CreatePhotocardUseCase`](src/main/java/com/stagelog/photocard/application/CreatePhotocardUseCase.java))와 셋리 하루 편집 한도([`EditSetlistUseCase`](src/main/java/com/stagelog/setlist/application/EditSetlistUseCase.java))는 센 뒤 넣으므로, 같은 사용자의 동시 요청 수만큼 넘을 수 있습니다. 남용 방지용이라 사용자 단위 잠금을 두지 않았습니다.
- **재계산 잠금의 교착 → 409.** 행이 없는 사용자의 첫 동시 쓰기나 여러 사용자를 도는 이벤트 재계산끼리 교착하면 InnoDB가 한쪽을 롤백합니다. 요청은 409, 리스너는 ERROR 로그로 끝납니다([`DistanceRecalculation`](src/main/java/com/stagelog/attendance/application/DistanceRecalculation.java), [`AttendanceCommand`](src/main/java/com/stagelog/attendance/application/AttendanceCommand.java)).
- **재생목록 동기화 잠금은 고정 TTL.** 동기화가 10분을 넘기면 잠금이 풀려 겹칠 수 있습니다([`RedisPlaylistSyncLock`](src/main/java/com/stagelog/setlist/infrastructure/RedisPlaylistSyncLock.java)).

## 개발 방식

- 이 저장소의 코드는 AI 코딩 도구(Claude Code)를 써서 작성했습니다.
- 그래서 설계 결정은 사람이 검토하고 다음 장치로 고정했습니다: 컨벤션 문서(요약은 아래), 경계를 빌드에서 검사하는 `ArchitectureTest`, 단위·통합·동시성 테스트, PR 본문을 AS-IS → 고려한 대안과 trade-off → TO-BE로 쓰는 규칙.
- 위 "핵심 설계 결정"과 "알려진 한계"는 그 PR과 코드 주석에서 옮겨 왔습니다.

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

마이그레이션 도구를 쓰지 않습니다([핵심 설계 결정](#핵심-설계-결정) 2). 새 DB는 `db/schema/*.sql`을 사전순으로 적용해 만들고(로컬·테스트는 MySQL 이미지 initdb), 운영 DB 변경은 `db/changes/<릴리스>/`의 SQL을 mysql 클라이언트로 적용한 뒤 검증하고 `schema_history`에 기록합니다. 애플리케이션은 모든 환경에서 `ddl-auto=validate`만 사용합니다. 파일 규칙(`IF NOT EXISTS` 금지, 이력 INSERT 위치, 변경 파일 이름)은 [`SchemaStaticTest`](src/test/java/com/stagelog/support/SchemaStaticTest.java)가 검사합니다.

## 환경 변수

`.env.example`에 이름과 설명이 있습니다. 대상 아티스트를 정하는 값은 `SETLISTFM_ARTIST_MBID`, `KOPIS_ARTIST_KEYWORD`, `TICKETMASTER_KEYWORD`, `NAVER_CALENDAR_PUBLISHED_KEY`, `YOUTUBE_PLAYLIST_TITLE_PREFIX`이며, 비어 있으면 해당 동기화는 건너뜁니다(재생목록 앞머리는 생략). 비밀값은 저장소에 두지 않고, S3는 AWS SDK 기본 자격 증명 체인(EC2 IAM 역할)을 씁니다.
