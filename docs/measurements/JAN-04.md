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
- 수정 전 커밋 / 결과:
- 수정 후 커밋 / 결과:
- 원본 로그와 테스트 경로:
- 해석과 한계:
