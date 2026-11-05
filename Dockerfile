# 앱(API + 미디어 변환 Worker) 실행 이미지. 변환 엔진은 FFmpeg CLI 이고 WebP 출력에 libwebp 가 필요하다
# runtime stage 만 따로 빌드하면 FFmpeg 실행 환경이 된다 (변환 통합 테스트가 Testcontainers 로 이 stage 를 띄운다)
FROM eclipse-temurin:21-jre-noble AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && ffmpeg -hide_banner -encoders | grep -q libwebp

# ./gradlew bootJar 후 빌드한다: docker build -t janspace-backend .
FROM runtime
WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
