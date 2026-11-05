# JAN-01 측정 리포트

- 목적: 서버가 여러 대일 때 같은 공급원 동기화가 동시에 실행되는지, 소유 서버가 죽은 뒤 다른 서버가 얼마 만에 이어받는지 수정 전후로 비교한다.
- 시나리오:
  - S1 동시 트리거: 같은 MySQL을 보는 `SyncRunner` 인스턴스 3개(각자 독립된 객체 = 서버 3대의 JVM 상태)를 만든다. 한 라운드마다 3개 스레드가 `CyclicBarrier`로 동시에 `run("KOPIS", body)`를 호출한다. body는 300ms 동안 실행 중 상태를 유지한다. 10ms 간격 sampler 스레드가 `sync_runs`를 읽는다. 100라운드.
  - S2 강제 종료 후 재획득 (in-process): 인스턴스 A가 body 안에서 멈춘 상태로 실행 중일 때, A를 "죽인다"(A의 Heartbeat를 멈추고 해제·종료 기록 없이 버린다. 수정 전 코드에는 Heartbeat가 없으므로 아무것도 멈추지 않는다). 그 시각부터 인스턴스 B가 50ms마다 `run`을 시도해 body가 처음 실행될 때까지의 시간을 잰다. 그다음 A의 body를 풀어 A가 종료 기록을 시도하게 하고, A run의 최종 상태를 본다. 20회.
  - S3 강제 종료 smoke (실제 프로세스): bootJar를 자식 JVM으로 띄워 NAVER 동기화를 시작시키고(응답하지 않는 Fake 네이버 서버), 자식이 RUNNING run을 남긴 상태에서 `kill -9`(`Process.destroyForcibly`, SIGKILL)한다. 테스트 JVM의 `SyncRunner`가 100ms마다 `run("NAVER", body)`를 시도해 재획득까지 걸린 시간을 잰다. 1회.
- 환경: Testcontainers MySQL 8.4(utf8mb4), Redis 7, 단일 호스트(macOS, Apple Silicon). 인스턴스 3개(S1), 2개(S2), 자식 프로세스 1개 + 테스트 JVM 1개(S3). 측정용 Lease 설정은 `jan.sync.lease.ttl-seconds=3`, `jan.sync.lease.heartbeat-seconds=1`(S1, S2), 자식 프로세스는 TTL 5초, Heartbeat 1초(S3). 운영 기본값(TTL 120초, Heartbeat 30초)보다 짧게 둔 것은 반복 시간을 줄이기 위해서다.
- 판정 기준: (측정 전에 작성)
  - 지표 정의
    - 동시 실행: 한 라운드에서 body가 동시에 2개 이상 실행 중이었던 순간이 있으면 1회로 센다 (메모리 counter로 잰 실제 실행 수).
    - 동시 RUNNING: 한 라운드에서 sampler가 같은 공급원의 `status = RUNNING` 행을 동시에 2개 이상 본 적이 있으면 1회로 센다.
    - 정상 실행 오종료: 이 시나리오의 body는 예외를 던지지 않으므로, 라운드 중 sampler가 그 라운드 run 중 하나라도 `FAILED`로 본 적이 있으면 "정상 실행 중인 run을 다른 인스턴스가 닫음" 1회로 센다.
    - 재획득 시간: S2·S3에서 "죽인" 시각부터 다른 인스턴스의 body가 처음 실행된 시각까지 (중앙값, 최대).
    - 복귀한 이전 소유자의 종료 기록 반영: S2에서 A의 body를 푼 뒤 A run의 최종 상태가 `OK`이면 1회로 센다.
  - 수정 후 성공 조건 (모두 만족해야 성공)
    1. S1 동시 실행 0/100
    2. S1 동시 RUNNING 0/100
    3. S1 정상 실행 오종료 0/100
    4. S2 재획득 성공 20/20, 재획득 시간 최대값 <= TTL + 1초 (= 4초)
    5. S2 이전 run이 재획득 시점에 `FAILED`이고 summary가 `LEASE_EXPIRED` 20/20
    6. S2 복귀한 이전 소유자의 종료 기록 반영 0/20
    7. S3 재획득 성공, 재획득 시간 <= 자식 TTL + 1초 (= 6초), 자식이 남긴 run이 `LEASE_EXPIRED`로 닫힘
  - 수정 전에는 1~3이 0보다 크고(인스턴스 사이에 CAS가 공유되지 않음), 4의 재획득 시간이 0에 가깝고(상호배제가 없어 기다리지 않음), 6이 0보다 클 것으로 예상한다. 예상과 달라도 기준은 바꾸지 않는다.
- 반복 횟수: S1 100라운드(인스턴스 3개 동시 트리거), S2 20회, S3 1회
- 수정 전 커밋 / 결과: `fb07b51` (`./gradlew bootJar` 후 `./gradlew measurementTest --tests '*Jan01*'`)
  - S1: 동시 실행 100/100, 동시 RUNNING 100/100, 정상 실행 오종료 0/100, body 실행 300회(라운드당 3.0)
  - S2: 재획득 성공 20/20, 재획득 시간 중앙값 20ms, 최대 217ms, 최소 8ms, `LEASE_EXPIRED`로 닫힘 0/20, 복귀한 이전 소유자의 종료 기록 반영 20/20 (재획득 시점의 이전 run은 20/20 모두 `FAILED`/"종료 기록 없이 남은 실행"으로 닫혔다가 복귀한 소유자가 `OK`로 덮어씀)
  - S3: 재획득 성공, 재획득 시간 148ms, 자식이 남긴 run은 `FAILED`/"종료 기록 없이 남은 실행" (`LEASE_EXPIRED` 아님)
- 수정 후 커밋 / 결과: `817de32` (같은 명령, bootJar 를 이 커밋에서 다시 빌드)
  - S1: 동시 실행 0/100, 동시 RUNNING 0/100, 정상 실행 오종료 0/100, body 실행 100회(라운드당 1.0) -> 기준 1·2·3 성공
  - S2: 재획득 성공 20/20, 재획득 시간 중앙값 2857ms, 최대 3091ms(<= 4초), 최소 2347ms, `LEASE_EXPIRED`로 닫힘 20/20, 복귀한 이전 소유자의 종료 기록 반영 0/20 -> 기준 4·5·6 성공
  - S3: 재획득 성공, 재획득 시간 4250ms(<= 6초), 자식이 남긴 run은 `FAILED`/`LEASE_EXPIRED` -> 기준 7 성공. kill 직전 자식의 Lease 행: `fencing_token=1`, `heartbeat_at` 이 획득 후 갱신됨, `expires_at = heartbeat_at + 5초`
  - 판정: 7개 기준 모두 성공
- 원본 로그와 테스트 경로:
  - 테스트: `src/test/java/com/stagelog/event/application/sync/SyncLeaseJan01MeasurementTest.java`
  - 수정 전: `docs/measurements/raw/JAN-01-before.txt`, 자식 프로세스 로그 `raw/JAN-01-before-child.log`
  - 수정 후: `docs/measurements/raw/JAN-01-after.txt`, 자식 프로세스 로그 `raw/JAN-01-after-child.log`
  - 수용 기준 assert: `SyncLeaseIntegrationTest`(동시 트리거 100회, 죽은 소유자 인계, Heartbeat 지연 주입, 정상 소유자 보호, 행사 행 잠금), `AdminSyncControllerIntegrationTest`(409), `SyncRunRecorderIntegrationTest`(stale 정리·fencing)
- 해석과 한계:
  - 수정 전 S1에서 인스턴스 3개가 100라운드 모두 동시에 본문을 실행했다. CAS 가 인스턴스 안에만 있어 서버 수만큼 동시 실행된다는 명세서의 추론이 재현됐다. 수정 후에는 라운드마다 1개만 실행되고 나머지 2개는 sync_runs 를 남기지 않고 건너뛴다.
  - 수정 전 S1의 "정상 실행 오종료"는 0/100으로 예상(0보다 큼)과 달랐다. 세 인스턴스가 같은 순간에 `start` 트랜잭션을 열어 서로의 RUNNING 행을 보지 못했기 때문이다(REQUIRES_NEW 트랜잭션이 동시에 커밋됨). 같은 결함은 시작 시점이 어긋난 S2에서 드러났다: 수정 전에는 다음 실행이 이전 run 을 20/20 즉시 FAILED 로 닫았고, 복귀한 이전 소유자가 그 기록을 20/20 `OK`로 덮어썼다.
  - 수정 전 재획득 시간(중앙값 20ms)이 짧은 것은 상호배제가 없어 기다리지 않기 때문이다. 수정 후 재획득 시간은 "마지막 Heartbeat + TTL - kill 시각 + 재시도 간격"이라 TTL 을 넘지 않는다. 측정 TTL 3초에서 최대 3.09초다(재시도 간격 50ms, 쿼리 시간 포함). 운영값(TTL 120초, Heartbeat 30초)에서는 같은 식으로 90~120초 + 다음 트리거 대기 시간이 된다. 스케줄 트리거는 cron 이라 실제 인계는 다음 cron 시각이나 관리자 수동 실행 때 일어난다.
  - S1·S2의 "서버 여러 대"는 한 JVM 안의 SyncRunner 인스턴스 여러 개다. 인스턴스마다 owner_id·Heartbeat 스레드·JVM 안 상태가 따로이고 같은 DB 를 보므로 DB 관점의 동작은 서버 여러 대와 같다. 다만 커넥션 풀과 CPU 를 공유하므로 네트워크 지연·서버 간 시계 차이는 재현하지 않는다. 시각 비교를 모두 DB NOW(6) 로 하므로 서버 시계 차이는 판정에 들어가지 않는다.
  - S2의 "죽이기"는 Heartbeat 실행기를 멈추고 본문 스레드를 붙잡아 두는 방식이다. 해제·종료 기록이 없다는 점에서 DB 상태는 kill -9 와 같다. 실제 kill -9 는 S3 에서 1회만 확인했다.
  - 측정 TTL 을 3초(S3 자식 5초)로 줄였다. Heartbeat 주기도 1초로 줄였으므로 TTL 대비 Heartbeat 비율(1/3, 1/5)은 운영값(1/4)과 비슷하다.
  - fencing token 은 sync_runs 의 종료 기록만 거른다. Lease 를 잃은 뒤 Heartbeat 가 상실을 확인하기 전(최대 Heartbeat 한 주기)에 이전 소유자가 쓴 행사 데이터는 막지 않는다. 그 사이 같은 행사를 새 소유자가 접어도 행사 행 잠금으로 직렬화되므로 "(event, field) 당 PENDING 1건" 규칙은 유지된다(행사 행 잠금을 빼면 같은 통합 테스트가 첫 라운드에 PENDING 2건으로 실패하는 것을 확인함).
  - raw 로그의 owner_id 에 있던 측정 호스트 이름은 공개 전에 `measure-host` 로 치환했다. 그 외 내용은 원본 그대로다.
