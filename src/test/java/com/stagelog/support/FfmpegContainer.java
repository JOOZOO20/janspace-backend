package com.stagelog.support;

import java.util.List;
import java.util.stream.Stream;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * 저장소 Dockerfile 의 runtime stage(Worker 와 같은 FFmpeg, libwebp 포함)를 JVM 당 한 번 띄운다. 앱은 docker exec -i prefix 로 그 FFmpeg 를 부른다.
 * 레이어 캐시를 쓰도록 이미지를 지우지 않는다. 빌드 컨텍스트에는 Dockerfile 만 넣는다(runtime stage 는 앱 jar 가 필요 없다).
 */
public final class FfmpegContainer {

    public static final GenericContainer<?> CONTAINER = new GenericContainer<>(
            new ImageFromDockerfile("janspace-media-ffmpeg:test", false)
                    .withFileFromPath("Dockerfile", ProjectPaths.projectRoot().resolve("Dockerfile"))
                    .withTarget("runtime"))
            .withCommand("sleep", "infinity");

    private FfmpegContainer() {
    }

    // 처음 부를 때 시작한다. 종료는 Testcontainers(Ryuk)가 JVM 종료 시 한다
    public static synchronized void start() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
    }

    // docker exec -i <컨테이너> + command
    public static List<String> exec(String... command) {
        start();
        return Stream.concat(Stream.of("docker", "exec", "-i", CONTAINER.getContainerId()), Stream.of(command)).toList();
    }

    // 앱 설정 jan.media.ffmpeg.command 형식 (쉼표 구분)
    public static String commandProperty(String binary) {
        return String.join(",", exec(binary));
    }
}
