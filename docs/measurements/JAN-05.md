# JAN-05 측정 리포트

- 목적:
  - 서버 측 이미지 변환 파이프라인(FFmpeg CLI, 비동기 Job, 우선순위 Queue)이 화면당 전송량을 줄이는지 측정한다.
  - Worker가 변환 중에 죽어도 rendition이 중복·누락 없이 끝나는지 측정한다.
  - 백필이 신규 원본 처리를 지연시키지 않는지 측정한다.
- 시나리오:
  1. 목록 화면 전송량
     - 샘플 원본 100장을 고정 순서로 20장씩 5개 화면으로 나눈다.
     - 화면마다 원본 bytes 합계와 `list` profile WebP rendition bytes 합계를 구한다.
  2. 변환 중 Worker 강제 종료
     - 원본 1장(rendition 6행)을 등록하고, Worker가 rendition을 claim해 FFmpeg를 실행하는 도중 Worker를 강제 종료한다.
     - 다른 Worker가 Zombie 회수(Lease 만료 → PENDING, 메시지 재발행) 후 처리를 끝낼 때까지 기다린다.
     - 강제 종료는 실행 계획 설계 판단 7에 따라 in-process로 재현한다. Worker 스레드를 Lease 해제·메시지 삭제·완료 UPDATE 없이 버린다. DB와 SQS에 남는 상태는 실제 kill과 같다.
     - 실제 `kill -9`는 bootJar 자식 프로세스로 1회 smoke한다.
  3. 백필 중 신규 업로드
     - 백필 1만 건을 `jan-media-backfill`에 발행한 상태에서 신규 원본 100건을 등록한다.
     - 신규 원본마다 처리 지연을 잰다. 처리 지연은 `media_assets.created_at`부터 그 asset의 rendition 6행이 모두 READY가 된 시각(`media_renditions.updated_at` 최댓값)까지다. 두 시각 모두 DB 시계 `NOW(6)`로 기록된 값이다.
     - 비교 조건 3개
       - A: 백필 없음 (신규 100건만)
       - B: 백필 1만 건 + `jan.media.queue.split=false` (신규와 백필이 같은 Queue)
       - C: 백필 1만 건 + `jan.media.queue.split=true` (신규 4회 수신마다 백필 1회)
- 환경:
  - 호스트: Apple M4 10코어, 메모리 16GB, macOS 15.7.8. Docker Desktop 28.1.1 (VM 6 CPU, 8GB)
  - JDK 21.0.7, Spring Boot 4.0.8
  - DB: Testcontainers MySQL 8.4 (utf8mb4)
  - AWS: LocalStack (S3, SQS)
  - FFmpeg: 저장소 `Dockerfile`의 `runtime` stage 이미지 (eclipse-temurin 21 JRE + 배포판 `ffmpeg` 패키지, libwebp 포함). 측정 시 `ffmpeg -version` 출력을 원본 로그에 남긴다.
  - Worker: 인스턴스 1개, `jan.media.worker.concurrency=2` (기본값). 시나리오 2만 Worker 2개(종료되는 쪽 1개, 회수 후 처리하는 쪽 1개)
  - Rendition profile: thumb 320px (WebP 75 / JPEG 80), list 720px (80 / 82), detail 1440px (85 / 85). 긴 변 기준이고 확대하지 않는다.
- 샘플 원본 100장 구성:
  - 출처: Lorem Picsum(`https://picsum.photos`)이 제공하는 Unsplash 사진
    - 실제 카메라 사진을 JPEG로 저장한 것이라 사진 원본과 같은 압축 특성(노이즈, 질감, 그라데이션)을 갖는다. 합성 이미지는 단색·패턴이 많아 압축률이 실제보다 크게 나오므로 쓰지 않는다.
    - 라이선스: Unsplash License. 상업·비상업 용도로 무료 사용, 수정, 배포가 허용되고 출처 표기 의무가 없다. 수정 없이 판매하는 것과 유사·경쟁 서비스를 만들려고 모으는 것은 금지된다 (https://unsplash.com/license).
  - 선정 방법
    - `https://picsum.photos/v2/list?page=1&limit=100`가 돌려주는 목록 순서대로 100개 ID를 쓴다.
    - 각 ID는 `https://picsum.photos/id/{id}/{w}/{h}` 로 아래 규격에 맞춰 받는다.
  - 규격별 구성 (목록 순서 기준)
    - 1~50번: 공연 포스터. 세로형 1000×1414 JPEG (A 계열 비율)
    - 51~70번: 앨범 이미지. 정사각형 1400×1400 JPEG
    - 71~90번: 포토카드. 1080×1350(4:5) PNG. 받은 JPEG를 디코딩해 PNG로 다시 저장한다. 클라이언트가 canvas에서 PNG로 export하는 현재 업로드 형식과 같게 만들기 위해서다.
    - 91~100번: 네컷. 1080×3240(1:3) PNG. 만드는 방법은 포토카드와 같다.
  - 재현성
    - ID, 작가, 받은 URL, 규격, 원본 sha256을 `raw/JAN-05-samples.tsv`에 기록한다. 다음 측정은 이 sha256과 같은 파일로만 한다.
    - 이미지 파일은 저장소에 커밋하지 않는다. 측정 테스트가 `build/` 아래에 받아 캐시한다.
- 판정 기준: (측정 전에 작성, 이후 변경하지 않음)
  - 시나리오 1 (전송량)
    - 성공: 5개 화면 모두에서 `list` WebP bytes 합계가 원본 bytes 합계보다 작다.
    - 화면별 감소율과 100장 전체 감소율을 함께 기록한다.
  - 시나리오 2 (Worker 강제 종료 50회)
    - 성공: 50회 모두 같은 (asset, profile, format)의 READY 행이 정확히 1개이고, S3에 그 행의 `output_key` 객체가 있다.
    - 성공: 누락 0건. 누락은 회수 대기 시간 상한이 지나도 READY도 FAILED도 아닌 rendition 행이다.
    - 회수 대기 시간 상한은 Lease 90초 + Zombie 회수 주기 1분 + 변환 제한 시간 60초 = 210초다.
    - 회수 시간(강제 종료 시각부터 READY까지)은 판정에 쓰지 않고 p50·p95·최댓값을 기록한다.
  - 시나리오 3 (백필 중 신규 업로드)
    - 성공: 조건 A의 신규 처리 지연 p95가 30초 이하다.
    - 성공: 조건 C의 신규 처리 지연 p95가 조건 A p95의 2배 이하다.
    - 조건 B는 Queue 분리 효과를 보이기 위한 비교값이고 판정에 쓰지 않는다.
  - 수용 기준 (통합 테스트 `@Tag("integration")`로 검증, 하나라도 실패하면 JAN-05 미완료)
    - 백필이 없을 때 신규 원본의 모든 rendition이 30초 안에 READY (p95). 시나리오 3 조건 A로 판정한다.
    - 변환 중 Worker 강제 종료 50회에서 같은 (asset, profile, format)의 READY 결과 1개, 누락 0건. 시나리오 2로 판정한다.
    - 잘린 JPEG, 확장자 위장 파일, 초대형 해상도 파일은 FFmpeg 실행 전에 FAILED(`INVALID_SOURCE`)가 되고 재시도 0회다. 재시도 0회는 rendition 행의 `attempt`가 1이고 메시지가 삭제된 것으로 확인한다.
    - EXIF Orientation 1~8 테스트 이미지 8장의 출력 방향이 정상이고, 출력에 EXIF·GPS가 없다.
      - 방향 정상: 출력의 가로·세로가 회전 후 기대값과 같고, 네 사분면의 색이 기대 위치에 있다.
      - EXIF·GPS 없음: 출력 bytes에 `Exif` 블록이 없고, 원본 EXIF에 넣은 표식 문자열과 GPS IFD가 출력에 없다.
    - 백필 1만 건 진행 중 신규 원본 처리 p95가 백필 없을 때의 2배 이내. 시나리오 3 조건 C로 판정한다.
    - rendition이 끝나지 않은 상태에서 API 응답의 모든 이미지 URL이 유효하다. READY가 아닌 자리에는 원본 URL이 들어간다.
- 반복 횟수:
  - 시나리오 1: 3회. FFmpeg 출력은 같은 입력·같은 이미지에서 결정적이어야 하므로 3회의 bytes가 모두 같은지도 확인한다. 다르면 3회 중앙값을 쓰고 그 사실을 기록한다.
  - 시나리오 2: 50회 (각 회차마다 새 asset)
  - 시나리오 3: 조건 A·B·C 각 3회. 회차마다 p95를 구하고, 판정은 3회 p95의 중앙값으로 한다.
- 수정 전 커밋 / 결과:
  - 커밋 `c78d94a` (측정 테스트만 추가한 커밋, 코드는 `eec5564` 와 같다). 이 브랜치의 작업 트리에는 구현이 커밋되지 않은 채 있었으므로, 측정 테스트만 커밋한 뒤 그 커밋을 임시 worktree 로 꺼내 실행했다. 임시 worktree 는 측정 후 지웠다.
  - 실행: `./gradlew measurementTest --tests '*Jan05*'` (2026-11-06, BUILD SUCCESSFUL). 원본 `raw/JAN-05-before.txt`
  - S1 (3회 모두 같은 값): 행사 목록 API 는 원본 URL 만 준다(images 필드 없음).

    | 화면 | 원본 bytes | list WebP bytes |
    |---|---:|---:|
    | 1 (포스터 1~20) | 3,183,982 | N/A |
    | 2 (포스터 21~40) | 3,410,436 | N/A |
    | 3 (포스터 41~50, 앨범 51~60) | 3,525,884 | N/A |
    | 4 (앨범 61~70, 포토카드 71~80) | 27,359,959 | N/A |
    | 5 (포토카드 81~90, 네컷 91~100) | 55,171,651 | N/A |
    | 합계 | 92,651,912 | N/A |

  - S2, S2 smoke, S3 조건 A·B·C: N/A. 변환 Worker·media 테이블·백필 API 가 없다(파이프라인 없음). 수정 전에는 원본을 그대로 서빙하므로 "신규 원본 처리 지연"이 정의되지 않는다.
  - 샘플 원본 manifest: `raw/JAN-05-samples.tsv` (Lorem Picsum ID, 작가, 받은 URL, 규격, bytes, sha256). 수정 후 측정은 이 sha256 과 같은 파일로만 돌린다(테스트가 검사한다).
- 수정 후 커밋 / 결과:
  - 커밋 `ff42fa4` (Job 파이프라인 구현. `127cdf0` 의 Queue 우선순위 수신 포함). 같은 명령으로 실행 (2026-11-06, BUILD SUCCESSFUL in 1h 20m 58s, Docker 중단 없음). 원본 `raw/JAN-05-after.txt`
  - S1 (3회 모두 같은 값, list WebP 출력 hash 도 3회 같음 = 인코딩이 결정적)

    | 화면 | 원본 bytes | list WebP bytes | 감소율 | 판정 |
    |---|---:|---:|---:|---|
    | 1 (포스터 1~20) | 3,183,982 | 872,980 | 72.6% | 성공 |
    | 2 (포스터 21~40) | 3,410,436 | 996,884 | 70.8% | 성공 |
    | 3 (포스터 41~50, 앨범 51~60) | 3,525,884 | 949,950 | 73.1% | 성공 |
    | 4 (앨범 61~70, 포토카드 71~80) | 27,359,959 | 1,171,718 | 95.7% | 성공 |
    | 5 (포토카드 81~90, 네컷 91~100) | 55,171,651 | 572,940 | 99.0% | 성공 |
    | 합계 | 92,651,912 | 4,564,472 | 95.1% | |

  - S2 (강제 종료 50회, 10회씩 5묶음): 중복 READY 0, 누락 0, READY 행의 S3 객체 없음 0 -> **성공**
    - 회수 시간 p50 90,489ms, p95 91,012ms, 최댓값 91,026ms (상한 210초 안). 50개 asset 모두 종료된 rendition 의 attempt 가 2 (재시도 1회로 끝남)
    - kill -9 smoke 1회: 자식 프로세스가 처리 중이던 rendition 이 이 JVM 에서 READY, attempt 2, 회수 89,204ms, READY 6/6, 중복 0, 미완료 0
  - S3 (신규 원본 100건, 3초 간격 도착)

    | 조건 | run 1 p95 | run 2 p95 | run 3 p95 | 중앙값 | 비고 |
    |---|---:|---:|---:|---:|---|
    | A 백필 없음 | 1,899ms | 1,873ms | 1,882ms | 1,882ms | 100/100 완료, p50 1,419~1,473ms |
    | B 백필 1만 + 분리 끔 | 미완료 | 미완료 | 미완료 | > 300초 | 상한(마지막 업로드 + 300초)까지 11~14건만 완료, 완료분 최댓값 553~575초 |
    | C 백필 1만 + 분리 켬 | 2,392ms | 2,344ms | 2,458ms | 2,392ms | 100/100 완료, p50 1,849~1,926ms |

    - 조건 A p95 1,882ms <= 30초 -> **성공**
    - 조건 C p95 2,392ms <= A 의 2배(3,764ms) -> **성공** (A 대비 1.27배)
    - 조건 B 는 판정에 쓰지 않는 비교값. 같은 Queue 에 백필이 3만~4만 메시지 쌓여 신규가 그 뒤에 선다
  - 수용 기준
    - 백필 없을 때 모든 rendition 30초 안에 READY (p95): 성공 (S3 A)
    - 강제 종료 50회 READY 1개·누락 0: 성공 (S2)
    - 잘린 JPEG·확장자 위장·초대형 해상도 -> FFmpeg 전에 FAILED(INVALID_SOURCE), 재시도 0회: 성공. `MediaPipelineIntegrationTest.invalid_sources_fail_without_retry` 가 rendition 18행 모두 attempt 1, last_error INVALID_SOURCE, 메시지는 영구 실패로 삭제(queue_message_failures permanent, attempts 1)를 확인하고, `FfmpegTranscodeIntegrationTest.invalid_sources_rejected_before_ffmpeg` 가 FFmpeg 호출 0회를 확인한다
    - EXIF Orientation 1~8 방향 정상, 출력에 EXIF·GPS 없음: 성공 (`FfmpegTranscodeIntegrationTest.exif_orientation_applied_and_metadata_stripped`, JPEG·WebP 각 8장)
    - 백필 진행 중 신규 p95 가 백필 없을 때의 2배 이내: 성공 (S3 C)
    - rendition 미완료 상태에서 API 응답의 모든 이미지 URL 유효: 성공 (`MediaPipelineIntegrationTest.unfinished_renditions_fall_back_to_original` 은 모든 자리가 원본 URL, `invalid_sources_fail_without_retry` 는 FAILED asset 의 응답 images 가 원본 URL)
- 원본 로그와 테스트 경로:
  - 수정 전 `raw/JAN-05-before.txt`, 수정 후 `raw/JAN-05-after.txt`, 샘플 manifest `raw/JAN-05-samples.tsv`
  - 수정 후 전체 출력은 메시지 1건마다 남는 INFO 로그(Queue 처리 완료, 미디어 변환 완료·건너뜀, 미디어 원본 등록, Outbox 발행, 출력이 원본보다 큼) 87,122줄 때문에 23MB 다. 이 줄만 뺀 것이 `raw/JAN-05-after.txt` 이고, 뺀 줄을 포함한 전체는 `raw/JAN-05-after-full.txt.gz` 다. 두 파일 모두 OS 사용자 이름과 로컬 경로만 치환했고 수치는 원본 그대로다
  - 측정 테스트: `src/test/java/com/stagelog/media/Jan05MediaMeasurementTest.java`(S1, S2, S3 A·C), `Jan05SplitOffMeasurementTest.java`(S3 B), `Jan05KillSmokeMeasurementTest.java`(kill -9 smoke), 공통 `Jan05MeasurementBase.java`, 샘플 `Jan05Samples.java`
  - 수용 기준 통합 테스트: `src/test/java/com/stagelog/media/MediaPipelineIntegrationTest.java`, `src/test/java/com/stagelog/media/infrastructure/FfmpegTranscodeIntegrationTest.java`
- 해석과 한계:
  - S1 감소율은 원본 형식이 좌우한다. 사진 JPEG(화면 1~3)는 약 71~73% 줄고, canvas export 를 흉내 낸 PNG(화면 4~5)는 96~99% 준다. 포토카드·네컷 PNG 는 받은 JPEG 를 JDK ImageIO 기본 설정으로 다시 저장한 것이라, 실제 브라우저 canvas 의 PNG 와 압축률이 다를 수 있다
  - S1 의 100장은 모두 포스터 업로드 경로(EventCommand.uploadPoster)로 넣었다. 변환 규칙이 소유자 종류와 무관해 같은 결과가 나오지만, 앨범·포토카드 API 응답을 직접 잰 것은 아니다
  - S2 의 회수는 모두 SQS 재전달(Visibility 90초)이 Lease(90초) 만료 직후 다시 claim 해서 끝났다. Zombie 회수(1분 주기)는 이번 측정에서 한 번도 행을 회수하지 않았다(재발행 0). Zombie 회수 경로는 `MediaPipelineIntegrationTest.zombie_recovery` 로만 확인했다
  - S2 의 강제 종료는 실행 계획 설계 판단 7에 따라 in-process 로 재현했다(변환 엔진 포트에서 FFmpeg 를 띄운 직후 Error 로 빠짐). DB·SQS 에 남는 상태는 실제 종료와 같지만 JVM 이 계속 살아 있다. 실제 kill -9 는 smoke 1회로만 확인했다
  - S3 신규 도착 간격 3초는 판정 기준에 없던 시나리오 조건이다. 이 호스트에서 Worker 2개의 처리량은 원본 1장(rendition 6개)당 약 0.94초(S1 에서 600 rendition 약 94초)라, 3초 간격은 백필 없을 때 처리량 안쪽이다. 도착이 더 몰리면 조건 A·C 모두 지연이 커진다
  - 백필 발행 속도는 설정 상한이 초당 20건이지만, 실제로는 원본 GET·디코딩·행 7개 쓰기가 한 thread 에서 돌아 초당 약 11건(5.5분에 3,700~3,850건)이었다. 그래도 백필 Queue 에 2만 메시지 이상 쌓여 우선순위 수신이 실제로 작동하는 조건이었다
  - 조건 B 의 p95 는 상한(300초) 안에 대부분 끝나지 않아 값이 아니라 "300초 초과"다. 실제 값은 쌓인 백필을 다 처리할 때까지(수 시간)이며 재지 않았다
  - 비율 수신은 대기 0초 수신으로 신규 Queue 가 비었는지 본다. LocalStack 은 비었을 때만 빈 응답을 주지만, 실제 SQS Short Polling 은 메시지가 있어도 빈 응답을 줄 수 있어 운영의 비율은 4:1 보다 백필 쪽으로 조금 기울 수 있다(추론, 운영 미측정)
  - 단일 호스트(Apple M4, Docker VM 6 CPU·8GB)이고 FFmpeg 를 docker exec 로 부르므로 호출마다 docker CLI 시간이 더해진다. 운영 Worker 이미지 안에서 ffmpeg 를 직접 실행하면 지연은 이보다 작을 것이다(추론)
