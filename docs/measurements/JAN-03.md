# JAN-03 측정 리포트

- 목적: 셋리스트가 조금 바뀌었을 때 YouTube Playlist에 보내는 쓰기 호출 수(= Quota unit)를 수정 전(전체 재구성)과 수정 후(Diff Sync)로 비교한다.
- 시나리오: 20곡 셋리스트(영상 ID `v01`~`v20`, 모두 서로 다른 곡)가 이미 Fake YouTube 서버의 Playlist에 같은 순서로 들어 있는 상태에서, 셋리스트(DB `setlist_items`)를 아래처럼 바꾸고 관리자 수동 동기화 진입점 `SyncPlaylistUseCase.syncOrThrow(eventId)`를 1번 호출한다. Fake 서버 요청 기록에서 동기화 1회 동안의 요청을 센다.
  - S1 변경 없음: 목표 = 현재와 같음
  - S2 1곡 교체: 10번째 곡 `v10`을 새 곡 `v21`로 바꿈
  - S3 앵콜 1곡 추가: 마지막에 1번째 곡 `v01`을 한 번 더 넣음 (같은 곡 두 번 등장)
  - S4 마지막 곡 삭제: `v20`을 뺌
  - S5 두 곡 위치 맞바꿈: 5번째 `v05`와 15번째 `v15`의 자리를 바꿈
- 환경: Testcontainers MySQL 8.4(utf8mb4), Redis 7, 단일 JVM(Spring Boot 통합 테스트 컨텍스트 1개). YouTube Data API는 JDK `com.sun.net.httpserver.HttpServer` 기반 Fake 서버(Playlist 상태를 메모리에 유지, `playlistItems.list` 페이지 크기 50)가 대신한다. 실제 `YouTubeClient`·`YouTubePlaylistAdapter`·`SyncPlaylistUseCase` 코드를 그대로 쓰고, HTTP 요청의 host만 테스트용 interceptor가 Fake 서버로 바꾼다. 측정 컨텍스트는 수동 bucket 예산을 크게 잡는다(`jan.youtube.quota.manual-units=1000000`, 수정 전 코드에는 없는 설정이라 무시된다).
- 판정 기준: (측정 전에 작성)
  - 지표 정의
    - 쓰기 횟수: 동기화 1회 동안 Fake 서버가 받은 `playlistItems` insert(POST)·update(PUT)·delete(DELETE) 요청 수. Playlist 생성(`playlists.insert`)은 이 측정에서 일어나지 않는다(Playlist가 이미 있음).
    - 쓰기 unit: 쓰기 횟수 × 50 (YouTube Data API 단가, 출처는 아래 해석 절)
    - 조회 unit: 같은 동기화 동안의 `playlistItems.list`·`playlists.list` 요청 수 × 1 (참고 지표, 판정에 쓰지 않음)
    - 일일 처리 가능 건수: `floor(10,000 / S2 쓰기 unit)` (S2 = 1곡 교체). 조회 unit까지 더한 값도 참고로 함께 적는다.
    - 결과 일치: 동기화 후 Fake 서버의 Playlist 영상 순서가 목표 영상 순서와 같으면 일치
  - 수정 후 성공 조건 (모두 만족해야 성공)
    1. S1 쓰기 0회 (0 unit)
    2. S2 쓰기 2회 이하 (100 unit 이하)
    3. S3 쓰기 1회 이하 (50 unit 이하)
    4. S4 쓰기 1회 이하 (50 unit 이하)
    5. S5 쓰기 2회 이하 (100 unit 이하)
    6. S1~S5 모두 결과 일치
    7. 일일 처리 가능 건수(쓰기 unit 기준) 100건 이상
  - 수정 전에는 S1 0회, S2 40회, S3 41회, S4 39회, S5 40회(삭제 20 + 삽입 N)로 예상한다. 예상과 달라도 기준은 바꾸지 않는다.
- 반복 횟수: 시나리오마다 3회(매번 새 행사와 새로 채운 Playlist). 쓰기 횟수는 결정적이므로 3회 값이 모두 같아야 하며, 다르면 그 사실을 기록한다.
- 수정 전 커밋 / 결과: `185a23c` (측정 테스트 추가 커밋, 동기화 코드는 develop `51aac0f`와 같음). 3회 반복 값이 모두 같았다.

  | 시나리오 | 쓰기 (unit) | 조회 unit | 결과 일치 |
  |---|---|---|---|
  | S1 변경 없음 | 0 (0) | 2 | 3/3 |
  | S2 1곡 교체 | 40 (2,000) | 2 | 3/3 |
  | S3 앵콜 1곡 추가 | 41 (2,050) | 2 | 3/3 |
  | S4 마지막 곡 삭제 | 39 (1,950) | 2 | 3/3 |
  | S5 두 곡 위치 맞바꿈 | 40 (2,000) | 2 | 3/3 |

  일일 처리 가능 "1곡 교체" 건수: 쓰기 unit 기준 5건, 조회 포함 4건. 판정: 기준 2·3·4·5·7 실패, 1·6 성공 (수정 전 예상과 같음).
- 수정 후 커밋 / 결과:
- 원본 로그와 테스트 경로:
- 해석과 한계:
