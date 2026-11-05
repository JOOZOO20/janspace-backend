# 측정 리포트

JAN-01~06 작업의 수정 전후 측정 결과를 `JAN-0X.md`에 기록한다. 판정 기준은 측정 전에 커밋하고, 측정 후에는 바꾸지 않는다.

## 진행 순서

1. 판정 기준(성공·실패 정의, 반복 횟수)을 리포트에 적고 커밋
2. 측정 테스트를 작성해 수정 전 커밋에서 실행하고 결과 기록
3. 수정 구현
4. 같은 테스트를 수정 후 커밋에서 같은 조건으로 실행
5. 리포트 완성

## 실행

```bash
./gradlew measurementTest --tests '*Jan01*'
```

- 측정 테스트는 `@Tag("measurement")`이고 `test`·`integrationTest`·`check`에서 제외된다.
- 원본 출력은 `raw/JAN-0X-before.txt`, `raw/JAN-0X-after.txt`에 둔다.

## 측정 환경

- DB: Testcontainers MySQL 8.4 (utf8mb4)
- AWS: LocalStack (S3, SQS)
- 외부 API: KOPIS·Naver 응답 고정, YouTube는 Playlist 상태를 메모리에 유지하는 Fake 서버
- 장애 주입: Toxiproxy(지연, 연결 끊김), 프로세스 강제 종료

## 리포트 형식

```markdown
# JAN-0X 측정 리포트
- 목적:
- 시나리오:
- 환경: (버전, 데이터 양, 인스턴스 수)
- 판정 기준: (측정 전에 작성)
- 반복 횟수:
- 수정 전 커밋 / 결과:
- 수정 후 커밋 / 결과:
- 원본 로그와 테스트 경로:
- 해석과 한계:
```
