# JAN-02 측정 리포트

- 목적: 이미지를 교체하면 URL도 바뀌는지(Cache Busting: 내용이 바뀌면 URL을 바꿔 캐시된 옛 파일이 나가지 않게 하는 기법), 같은 내용을 다시 받을 때 S3 PUT이 생기지 않는지 수정 전후로 비교한다.
- 시나리오:
  - S1 포스터 교체: 행사 1개에 관리자 포스터 업로드(`EventCommand.uploadPoster`)로 첫 이미지를 올린 뒤, 내용이 서로 다른 PNG 50개로 차례로 교체한다. 교체마다 반환된 공개 URL을 직전 URL과 비교한다.
  - S2 앨범커버 교체: 앨범 1개에 커버 업로드(`CatalogCommand.uploadCover`)로 같은 방식의 교체 50회.
  - S3 같은 내용 재업로드: S1이 끝난 행사에 마지막 포스터와 바이트가 같은 파일을 10회 다시 올리고, 그동안의 S3 PutObject 호출 수를 센다.
  - S4 KOPIS 같은 포스터 재전송: 미연결 행사 1개를 KOPIS 보강(`KopisEnrichment.enrich`)으로 연결(첫 실행)한 뒤, 같은 공연·같은 포스터가 증분 목록에 다시 오는 실행을 10회 반복한다. 10회 동안의 S3 PutObject 호출 수를 센다. 이어서 KOPIS가 내용이 다른 포스터를 1회 보내는 실행을 하고, 행사 포스터 URL이 바뀌었는지 본다(보조 지표).
- 환경: LocalStack 3.8.1(S3, Testcontainers), MySQL 8.4(utf8mb4, Testcontainers), Redis 7, 단일 호스트(macOS, Apple Silicon), 애플리케이션 인스턴스 1개. KOPIS 응답은 `MockRestServiceServer` 픽스처로 고정한다. S3 PutObject 수는 실제 LocalStack을 부르는 `S3Client`를 Mockito spy로 감싸 센다. 공개 URL 기본 주소는 LocalStack 버킷 주소(경로 방식)다.
- 판정 기준: (측정 전에 작성)
  - 지표 정의
    - URL 재사용: 교체 1회 후 반환 URL이 교체 직전 URL과 문자열로 같으면 1회로 센다.
    - 내용 불일치: 교체 1회 후 반환 URL을 HTTP GET으로 받은 본문의 sha256이 방금 올린 파일의 sha256과 다르면 1회로 센다.
    - PUT 수: 시나리오 구간 동안 `S3Client.putObject` 호출 횟수.
    - KOPIS 포스터 변경 반영: S4 마지막 실행 후 행사 포스터 URL이 그 직전 URL과 다르면 "반영"으로 본다.
  - 수정 후 성공 조건 (모두 만족해야 성공)
    1. S1 URL 재사용 0/50
    2. S2 URL 재사용 0/50
    3. S1·S2 내용 불일치 0/50
    4. S3 PUT 0회 (같은 내용 10회 재업로드)
    5. S4 PUT 0회 (같은 포스터 10회 재전송)
    6. S4 KOPIS 포스터 변경 반영
  - 수정 전에는 1·2가 50/50(같은 key에 덮어씀), 4가 10회일 것으로 예상한다. 5는 수정 전 코드가 "이미 S3 key가 있으면 다시 받지 않는다"는 prefix 비교를 쓰므로 0회, 대신 6이 "반영 안 됨"일 것으로 예상한다. 예상과 달라도 기준은 바꾸지 않는다.
  - 3은 LocalStack 앞에 캐시가 없어 수정 전에도 0일 수 있다. 같은 URL에 덮어쓴 경우 브라우저·CDN 캐시가 옛 파일을 줄 수 있다는 위험은 1·2(URL 재사용)로 판정한다.
- 반복 횟수: S1 50회, S2 50회, S3 10회, S4 10회 + 변경 1회. 결과가 입력에 대해 결정적이므로 시나리오 전체는 1회 실행한다.
- 수정 전 커밋 / 결과: `c79bb64` (측정 테스트 커밋, 수정 전 코드)
  - S1 포스터 교체: URL 재사용 50/50, 내용 불일치 0/50
  - S2 앨범커버 교체: URL 재사용 50/50, 내용 불일치 0/50
  - S3 같은 내용 재업로드 10회: S3 PUT 10회
  - S4 KOPIS 첫 연결 PUT 1회, 같은 포스터 재전송 10회: S3 PUT 0회, 포스터 변경 1회: 반영 안 됨 (`events/{id}/poster.gif` 유지)
  - 판정: 1·2·4·6 실패, 3·5 통과 (예상과 같음)
- 수정 후 커밋 / 결과: `320d191` (수정 커밋 `27c611e` 버전 key·삭제 배치, `320d191` 백필)
  - S1 포스터 교체: URL 재사용 0/50, 내용 불일치 0/50
  - S2 앨범커버 교체: URL 재사용 0/50, 내용 불일치 0/50
  - S3 같은 내용 재업로드 10회: S3 PUT 0회
  - S4 KOPIS 첫 연결 PUT 1회, 같은 포스터 재전송 10회: S3 PUT 0회, 포스터 변경 1회: 반영 (`events/{id}/poster/697435dc8e986ac4.gif` -> `.../2e732a25bbf95f29.gif`)
  - 판정: 성공 조건 1~6 모두 통과
- 원본 로그와 테스트 경로:
  - 원본: `docs/measurements/raw/JAN-02-before.txt`, `docs/measurements/raw/JAN-02-after.txt`
  - 측정 테스트: `src/test/java/com/stagelog/integration/s3/Jan02StorageKeyMeasurementTest.java` (`./gradlew measurementTest --tests '*Jan02*'`)
  - 수용 기준 통합 테스트: `src/test/java/com/stagelog/integration/s3/StorageKeyIntegrationTest.java` (교체 후 새 URL·sha256 일치·`Cache-Control` immutable, 같은 내용·KOPIS 재전송 PUT 0, 이전 key 7일 보존 후 삭제, 참조 중 key 보존, 백필, 플래그 off)
- 해석과 한계:
  - 수정 전에는 교체마다 같은 key에 덮어써 URL이 100% 재사용됐다. 앞에 캐시(브라우저, CDN)가 있으면 교체 후에도 옛 이미지가 나갈 수 있는 구조다. 수정 후에는 key에 내용 sha256이 들어가 교체 URL이 매번 달라지고, 같은 내용은 HEAD 200으로 PUT을 건너뛴다.
  - KOPIS 재전송 PUT은 수정 전에도 0회였다. 수정 전 코드가 "이미 S3 key가 있으면 다시 받지 않는다"로 PUT을 막았기 때문이며, 그 대가로 KOPIS가 포스터를 바꿔도 반영되지 않았다(S4 변경 "반영 안 됨"). 수정 후에는 매번 내려받아 내용으로 비교하므로 PUT 0회를 유지하면서 변경은 반영된다. 대신 KOPIS 실행마다 포스터 다운로드 1회와 S3 HEAD 1회가 생긴다.
  - 내용 불일치는 전후 모두 0이다. LocalStack 앞에 캐시가 없어서이고, 캐시로 인한 옛 이미지 노출 자체는 이 환경에서 재현하지 않았다. URL 재사용 수로 위험을 판정했다.
  - 실제 AWS가 아니라 LocalStack 3.8.1이다. S3 HeadObject는 버킷 `s3:ListBucket` 권한이 없으면 없는 key에 404 대신 403을 준다([AWS HeadObject API 문서](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html) Permissions 절). 코드는 404만 "없음"으로 보고 403은 예외로 올린다(덮어쓰기 방지). 운영 IAM 역할에 ListBucket이 없으면 업로드가 실패하므로 운영 반영 전에 권한을 확인해야 한다.
  - `immutable`은 RFC 8246의 Cache-Control 확장으로, 유효 기간 안에는 재검증 요청도 보내지 않게 한다. key가 내용 hash라 같은 URL의 내용이 바뀌지 않으므로 안전하다.
  - 7일 경과는 실제로 기다리지 않고 `delete_after`를 DB 시계 기준 과거로 옮겨 검증했다(통합 테스트).
