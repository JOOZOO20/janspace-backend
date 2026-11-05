# JAN-04 측정 리포트

- 목적: 공급원 수집과 후처리(병합, 포스터 입수)를 SQS 메시지로 나눈 뒤, KOPIS 항목 한 건의 실패가 실행 전체를 멈추지 않는지, 포스터 다운로드 지연이 동기화 시간에 묶이지 않는지, At-least-once delivery(같은 메시지가 한 번 이상 전달될 수 있는 전달 보장) 환경의 중복·역순 전달에서도 병합 결과가 같은지, Transactional Outbox(DB 쓰기와 같은 트랜잭션에 발행할 메시지를 기록해 두고 별도 Relay가 발행하는 패턴)가 발행 전 서버 종료에도 메시지를 잃지 않는지 수정 전후로 비교한다.
- 시나리오:
  - S1 KOPIS 건별 격리: 미연결 활성 행사 50개(날짜가 모두 다름)가 각각 KOPIS 공연 1개와 정확히 매칭되도록 KOPIS 응답을 고정한다. 매 반복마다 무작위 위치(seed 고정) 1건의 상세 응답을 잘린 XML로 바꿔 파싱 오류를 낸 뒤 `KopisEnrichment.enrich`를 1회 실행한다. 반복마다 행사·출처·실행 이력을 비운다.
  - S2 포스터 다운로드 지연: 미연결 행사 2개를 KOPIS 보강으로 연결하는 실행을 10회 반복한다. 포스터 URL은 Toxiproxy(TCP 프록시에 지연·끊김을 주입하는 장애 주입 도구)를 거쳐 테스트 JVM의 HTTP 서버를 부르고, Toxiproxy에 응답 방향 5,000ms latency toxic을 건다. 실행마다 `enrich` 호출 시작부터 반환까지 걸린 시간을 잰다. 반복마다 행사를 새로 만든다.
  - S3 apply 메시지 중복·순서 섞기: 행사 1개에 NAVER·KOPIS·TICKETMASTER·SETLISTFM 출처 4개를 저장하고(필드 충돌로 PENDING 제안이 생기게 시작 시각을 다르게 둔다), `jan-source-apply` 메시지 5개(4개 출처 + 이전 내용 hash를 가진 KOPIS 메시지 1개)를 각 1~3회 복제한 뒤 순서를 무작위로 섞어 LocalStack SQS에 직접 보낸다. Queue가 빌 때까지 기다린 뒤 결과를 기준값과 비교한다. 기준값은 같은 출처를 저장한 별도 행사에 `ApplySourcesUseCase.refold`를 1회 실행한 결과다. 100회 반복하고 반복마다 행사를 새로 만든다.
  - S4 Relay 발행 전 서버 종료: Relay를 끈 상태(`jan.queue.relay.enabled=false`)에서 KOPIS 보강으로 행사 2개의 출처를 저장한다. 출처 저장 커밋과 Outbox 기록은 끝났고 Relay는 발행하지 않은 상태이므로, DB와 SQS 상태는 그 시점에 서버가 죽은 것과 같다. 이어서 Relay 1회 실행으로 재기동을 재현하고 Consumer가 처리할 때까지 기다린다. 50회 반복한다. 추가로 bootJar 자식 프로세스를 SQS에 닿지 않는 endpoint로 띄워 KOPIS 동기화가 Outbox만 남기게 한 뒤 `kill -9`하고, 테스트 JVM의 Relay가 남은 행을 발행하는지 1회 smoke로 확인한다.
- 환경: MySQL 8.4(utf8mb4, Testcontainers), Redis 7, LocalStack 3.8.1(S3·SQS, Testcontainers), Toxiproxy 2.1.4(Testcontainers), 단일 호스트(macOS, Apple Silicon), 애플리케이션 인스턴스 1개(S4 smoke만 자식 프로세스 1개 추가). KOPIS 목록·상세 응답은 `MockRestServiceServer`로 고정하고, S2의 포스터 요청만 `ExecutingResponseCreator`로 실제 HTTP(Toxiproxy 경유)를 탄다.
- 판정 기준: (측정 전에 작성)
  - 지표 정의
    - S1 정상 항목 저장 비율: 실행 후 KOPIS 출처(`event_sources`)가 연결된 정상 행사 수 / 49. 반복 50회의 평균과 최솟값.
    - S1 실행 상태: `sync_runs.status`. 실패 기록: 그 실행의 `sync_item_failures` 행 수. 커서 미전진: 실행 후 `SyncRunQuery.lastSucceededAt("KOPIS")`가 실행 전과 같으면 미전진.
    - S2 동기화 실행 시간: `enrich` 시작부터 반환까지의 wall-clock ms. 10회의 중앙값·최댓값. 보조 지표로 실행 후 60초 안에 행사 포스터가 S3 key로 바뀐 행사 수.
    - S3 결과 불일치: 반복 1회의 결과가 기준값과 하나라도 다르면 1건. 비교 항목은 행사 필드(이름, 설명, 예매 URL, 포스터 key, 공연장 ID, 날짜, 시작 시각, 공연 시간, 상태), PENDING 제안 수, 변경 이력(`event_source_changes`) 행 수와 각 행의 (field, status, proposed_value).
    - S4 메시지 유실: 재기동(Relay 실행) 후 30초 안에 병합이 반영되지 않은(KOPIS 이름으로 바뀌지 않은) 행사 수. 2개 × 50회 = 100개 중.
  - 수정 후 성공 조건 (모두 만족해야 성공)
    1. S1 정상 항목 저장 비율 50회 모두 100% (49/49)
    2. S1 실행 상태 50회 모두 PARTIAL, 실패 기록 50회 모두 1건, 커서 50회 모두 미전진
    3. S2 동기화 실행 시간 10회 모두 5,000ms 미만 (포스터 지연 1회분보다 짧다 = 포스터 지연과 무관)
    4. S2 보조 지표: 포스터가 S3 key로 반영된 행사 20/20
    5. S3 결과 불일치 0/100
    6. S4 메시지 유실 0/100, smoke에서 kill -9 후 남은 Outbox 행 전부 발행
  - 수정 전 예상: S1은 첫 오류에서 실행이 멈춰 저장 비율이 오류 위치에 따라 0~100%(평균 약 50%)이고 상태는 FAILED다. S2는 포스터 2개를 동기화 안에서 받으므로 실행마다 10,000ms 이상이다. S3·S4는 Queue와 Outbox가 없어 측정할 수 없다(N/A). 예상과 달라도 기준은 바꾸지 않는다.
- 반복 횟수: S1 50회, S2 10회, S3 100회, S4 50회 + kill -9 smoke 1회.
- 수정 전 커밋 / 결과: `940e35b` (측정 테스트 커밋, 수정 전 코드)
  - S1 KOPIS 건별 격리 50회: 정상 항목 저장 비율 평균 46.9%, 최소 0.0%. 실행 상태 FAILED 50/50, PARTIAL 0/50, 실패 기록 1건 0/50(테이블 없음), 커서 미전진 50/50
  - S2 포스터 5초 지연 10회: 동기화 시간 중앙값 10,320ms, 최대 10,958ms, 최소 10,291ms, 5,000ms 미만 0/10. 포스터 S3 반영 20/20
  - S3 apply 중복·순서 섞기: N/A (Queue Consumer 없음)
  - S4 Relay 발행 전 종료: N/A (Outbox·Relay 없음), kill -9 smoke: N/A
  - 판정: 성공 조건 1·2·3·5·6 실패(5·6은 측정 불가), 4 통과. 예상과 같음
  - 재실행: 아래 측정 테스트 수정(`a4b6a3f`) 뒤 같은 수정 전 코드(`940e35b`)에 고친 테스트를 올려 다시 돌렸다. S1은 같은 결과(seed 고정), S2는 중앙값 10,193ms, 최대 10,543ms, 최소 10,162ms, 5,000ms 미만 0/10, 포스터 S3 반영 20/20. S3·S4·smoke는 N/A
- 수정 후 커밋 / 결과: 수정 커밋 `b20ca80`(Outbox·Relay·Consumer·DLQ 기반), `e5fd31a`(KOPIS 건별 격리, 수집·후처리 분리). S1~S4는 `a4b6a3f`, kill -9 smoke는 `fce61bc`에서 실행했다(둘 다 `e5fd31a`에 측정 테스트 수정만 더한 커밋)
  - S1 KOPIS 건별 격리 50회: 정상 항목 저장 비율 평균 100.0%, 최소 100.0%. 실행 상태 PARTIAL 50/50, 실패 기록 1건 50/50, 커서 미전진 50/50
  - S2 포스터 5초 지연 10회: 동기화 시간 중앙값 42ms, 최대 143ms, 최소 28ms, 5,000ms 미만 10/10. 포스터 S3 반영 20/20 (포스터 Consumer 가 비동기로 반영)
  - S3 apply 중복·순서 섞기 100회(전달 메시지 995건): 결과 불일치 0/100
  - S4 Relay 발행 전 종료 50회: 메시지 유실 0/100. 발행 전 PENDING 합계 102건(첫 반복의 2건은 바로 앞 smoke 시도가 남긴 행이며 함께 발행됨)
  - S4 kill -9 smoke 1회: 자식 프로세스가 apply Outbox 2건을 남기고 발행에 실패(attempts ≥ 1)한 상태에서 kill -9 → 테스트 JVM 의 Relay 1회로 미발행 행 0, 유실 0/2, 두 행사 모두 KOPIS 이름으로 병합
  - 판정: 성공 조건 1~6 모두 통과
  - 측정 테스트 수정 이력(판정 기준·반복 횟수는 바꾸지 않음): 첫 수정 후 실행(`e5fd31a`, 원본 `raw/JAN-04-after-run1.txt`)에서 두 가지 테스트 결함이 드러났다. (1) S1이 반복마다 `sync_runs`만 비워 실행 ID가 1부터 다시 시작하는데 `sync_item_failures`는 남아, 같은 실행 ID로 이전 반복의 실패까지 세어 "실패 기록 1건"이 1/50으로 나왔다(반복 k의 값이 k+1). (2) smoke의 자식 프로세스는 실제 오늘 날짜로 보강 범위를 정하는데 행사를 고정 날짜로 만들어 매칭되지 않았고, 고친 뒤에도 대기 조건이 "attempts > 0 인 행 2건"이라 Relay가 첫 실패에서 주기를 멈추는 동작(가장 오래된 행만 attempts 증가)과 맞지 않아 건너뛰었다. `a4b6a3f`·`fce61bc`에서 테스트만 고쳤다. 첫 실행의 나머지 수치는 S1 저장 비율 100%·PARTIAL 50/50·커서 미전진 50/50, S2 중앙값 54ms·5,000ms 미만 10/10, S3 불일치 0/100, S4 유실 0/100으로 재실행과 같은 판정이다
- 원본 로그와 테스트 경로:
  - 원본: `docs/measurements/raw/JAN-04-before.txt`(수정 전), `raw/JAN-04-before-rerun.txt`(수정 전 코드 + 고친 테스트), `raw/JAN-04-after-run1.txt`(첫 수정 후 실행, 테스트 결함 포함), `raw/JAN-04-after.txt`(수정 후 S1~S4 + kill -9 smoke)
  - 원본은 gradle 전체 출력(수 MB, 대부분 Hibernate·앱 INFO 로그)에서 gradle task·테스트 경계·`JAN-04 측정` 줄·동기화/Queue/Outbox/DLQ 이정표·WARN/ERROR 첫 줄만 grep 으로 남기고 사용자 경로·호스트 이름을 치환했다
  - 측정 테스트: `src/test/java/com/stagelog/event/application/sync/Jan04KopisIsolationMeasurementTest.java`(S1), `Jan04QueueMeasurementTest.java`(S2·S3·S4·smoke). 실행: `./gradlew bootJar measurementTest --tests '*Jan04*'`
  - 수용 기준 통합 테스트: `src/test/java/com/stagelog/event/application/sync/IngestQueueIntegrationTest.java` (중복 3회 = 1회, 순서 섞기 100회, KOPIS 1건 오류 PARTIAL·실패 기록·커서, 포스터 5xx 재전달 성공·404 영구 실패 원본 URL 유지, Consumer 처리 중 종료 후 재전달, Relay 발행 전 종료 후 유실 0, DLQ 조회·redrive)
- 해석과 한계:
  - S1: 수정 전에는 첫 오류 위치 앞의 항목만 저장되고 실행이 FAILED 라 저장 비율이 오류 위치에 그대로 비례했다(평균 46.9%, 위치 0이면 0%). 수정 후에는 오류 항목만 `sync_item_failures`에 남고 나머지 49건이 모두 저장되며, 실행이 PARTIAL 이라 `lastSucceededAt`(OK 만 봄)이 바뀌지 않아 다음 실행이 같은 afterdate 구간을 다시 가져온다. 단, 연속 3건 실패는 KOPIS 장애로 보고 FAILED 로 멈추게 했다(타임아웃이 행사 수만큼 곱해지는 것을 막던 원래 fail-fast 의 이유). 이 상한은 측정 시나리오(1건 오류)에 걸리지 않는다.
  - S2: 수정 전 동기화 시간(약 10.2~11초)은 포스터 2개 × 5초 지연이 그대로 더해진 값이다. 수정 후에는 동기화가 출처 저장과 Outbox 기록만 하므로 지연과 무관하게 28~143ms 이고, 포스터는 jan-poster-ingest Consumer 가 지연을 견디며 받아 S3 key 로 바꾼다(20/20). 대신 첫 연결 직후 포스터가 반영되기 전까지 행사 포스터는 KOPIS 원본 URL 이다.
  - S3: 병합 Consumer 는 메시지 내용이 아니라 DB 에 저장된 모든 출처로 다시 접으므로, 중복·역순(옛 hash 메시지 포함) 전달에도 결과가 같다. 이 시나리오는 출처가 모두 저장된 뒤 메시지를 전달한다. 출처 저장과 접기가 서로 다른 시점에 엇갈리면 중간 접기가 만든 PENDING 제안이 달라질 수 있는데, 이것은 공급원 동기화 순서에 따라 이미 같은 일이 생기는 기존 접기 정책(값 -> 다른 값 제안)의 성질이라 이 측정 범위 밖이다.
  - S4: 프로세스를 실제로 50번 죽이지 않고 "Relay 가 돌지 않은 상태"(`jan.queue.relay.enabled=false`)로 재현했다. DB·SQS 상태는 커밋 직후 서버가 죽은 것과 같다. 실제 kill -9 는 bootJar 자식 프로세스로 1회 확인했다. Relay 가 SQS 발행 성공 뒤 PUBLISHED 커밋 전에 죽는 경우는 유실이 아니라 중복 발행이 되며(Consumer 가 중복을 견딘다) 이 측정에 포함하지 않았다.
  - 실제 AWS SQS 가 아니라 LocalStack 3.8.1 이다. redrive policy 의 maxReceiveCount 는 "소스 Queue 에서 받을 수 있는 횟수"이고 넘으면 DLQ 로 옮긴다([SQS dead-letter queue 문서](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)). Visibility Timeout 은 첫 수신부터 최대 12시간이고 ChangeMessageVisibility 로 늘려도 이 한도는 다시 시작하지 않는다([SQS visibility timeout 문서](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html)). backoff 최대 30분 + 처리 시간은 이 한도 안이다.
  - 통합 테스트는 재전달이 빨리 오도록 Visibility 3초, backoff 1초로 줄였다. 운영 값(apply 60초·poster 120초, 30초·2분·10분·30분)의 시간 동작은 단위 테스트(`QueuePropertiesTest`)로만 확인했다.
