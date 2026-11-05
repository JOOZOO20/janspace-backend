package com.stagelog.media.infrastructure;

import com.stagelog.media.application.TranscodeException;
import com.stagelog.media.application.TranscodeException.Reason;
import com.stagelog.media.application.port.ImageTranscoderPort;
import com.stagelog.media.domain.RenditionFormat;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * FFmpeg CLI 변환 어댑터. 원본은 stdin, 출력은 stdout pipe 로 주고받아 임시 파일을 만들지 않는다.
 * 실행 명령은 prefix 로 받는다. Worker 이미지 안에서는 ffmpeg 그대로, 로컬·테스트는 docker exec -i 컨테이너 ffmpeg 처럼 컨테이너 안의 FFmpeg 를 쓴다.
 * docker exec prefix 에서는 시간 초과 시 docker 클라이언트만 종료되고 컨테이너 안의 ffmpeg 는 남으므로, 운영은 Worker 이미지 안의 ffmpeg 를 직접 실행한다.
 */
@Slf4j
@Component
public class FfmpegImageTranscoder implements ImageTranscoderPort {

    private final List<String> ffmpeg;
    private final List<String> ffprobe;
    private final Duration timeout;

    public FfmpegImageTranscoder(@Value("${jan.media.ffmpeg.command:ffmpeg}") List<String> ffmpeg,
                                 @Value("${jan.media.ffmpeg.probe-command:ffprobe}") List<String> ffprobe,
                                 @Value("${jan.media.transcode.timeout:60s}") Duration timeout) {
        this.ffmpeg = List.copyOf(ffmpeg);
        this.ffprobe = List.copyOf(ffprobe);
        this.timeout = timeout;
    }

    // 변환이 켜진 프로세스는 기동 시 WebP encoder(libwebp)를 확인하고, 없으면 기동을 멈춘다. 없는 채로 돌면 WebP rendition 이 모두 FAILED 가 된다
    @EventListener(ApplicationStartedEvent.class)
    public void verifyEncoders(ApplicationStartedEvent event) {
        if (!event.getApplicationContext().getEnvironment().getProperty("jan.media.transcode.enabled", Boolean.class, false)) {
            return;
        }
        List<String> command = new ArrayList<>(ffmpeg);
        command.addAll(List.of("-hide_banner", "-encoders"));
        Result result = run(command, new byte[0]);
        if (result.exitCode() != 0 || !new String(result.stdout(), StandardCharsets.UTF_8).contains("libwebp")) {
            throw new IllegalStateException("FFmpeg 에 libwebp encoder 가 없습니다. Worker 이미지(Dockerfile)의 ffmpeg 를 쓰세요. command=" + ffmpeg);
        }
        log.info("[FFmpeg 확인] libwebp encoder 있음 command={}", ffmpeg);
    }

    @Override
    public byte[] transcode(byte[] source, int orientation, int width, int height, RenditionFormat format, int quality, boolean alpha) {
        List<String> command = new ArrayList<>(ffmpeg);
        // 1. 입력: EXIF 자동 회전은 FFmpeg 버전마다 달라 끄고, 회전은 아래 filter 로 직접 한다
        command.addAll(List.of("-hide_banner", "-nostdin", "-v", "error", "-noautorotate", "-i", "pipe:0"));
        // 2. 메타데이터 제거(EXIF·GPS·인코더 이름) 후 회전 → 축소
        command.addAll(List.of("-map_metadata", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
                "-vf", orientationFilter(orientation) + "scale=" + width + ":" + height + ":flags=lanczos", "-frames:v", "1"));
        // 3. 인코딩
        command.addAll(switch (format) {
            case WEBP -> List.of("-c:v", "libwebp", "-quality", String.valueOf(quality),
                    "-pix_fmt", alpha ? "yuva420p" : "yuv420p", "-f", "webp");
            case JPEG -> List.of("-c:v", "mjpeg", "-q:v", String.valueOf(mjpegQscale(quality)),
                    "-pix_fmt", "yuvj420p", "-f", "image2pipe");
            case PNG -> List.of("-c:v", "png", "-pix_fmt", alpha ? "rgba" : "rgb24", "-f", "image2pipe");
        });
        command.add("pipe:1");

        Result result = run(command, source);
        if (result.exitCode() != 0) {
            throw new TranscodeException(Reason.FFMPEG_FAILED, "ffmpeg 종료 코드 " + result.exitCode() + ": " + result.stderr());
        }
        return result.stdout();
    }

    @Override
    public Probe probe(byte[] image) {
        List<String> command = new ArrayList<>(ffprobe);
        // -count_frames 로 실제 디코딩까지 한다. 헤더만 읽으면 잘린 출력도 통과한다
        command.addAll(List.of("-v", "error", "-count_frames", "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height,nb_read_frames", "-of", "default=noprint_wrappers=1", "-i", "pipe:0"));
        Result result = run(command, image);
        if (result.exitCode() != 0) {
            throw new TranscodeException(Reason.OUTPUT_INVALID, "ffprobe 종료 코드 " + result.exitCode() + ": " + result.stderr());
        }
        Map<String, String> fields = new HashMap<>();
        for (String line : new String(result.stdout(), StandardCharsets.UTF_8).split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                fields.put(line.substring(0, eq), line.substring(eq + 1).trim());
            }
        }
        return new Probe(fields.getOrDefault("codec_name", ""),
                intOrZero(fields.get("width")), intOrZero(fields.get("height")), intOrZero(fields.get("nb_read_frames")));
    }

    /**
     * 명령을 실행해 stdin 을 넣고 stdout·stderr 를 모은다. 제한 시간을 넘기면 프로세스와 자식 프로세스를 강제 종료한다.
     * 세 pipe 를 동시에 처리해야 pipe 버퍼가 차서 서로 기다리는 일이 없다.
     */
    Result run(List<String> command, byte[] stdin) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new TranscodeException(Reason.FFMPEG_FAILED, "프로세스 시작 실패 command=" + command.getFirst(), e);
        }
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            io.submit(() -> {
                // FFmpeg 가 입력을 다 읽기 전에 끝나면 broken pipe 가 난다. 결과는 종료 코드로 판단한다
                try (OutputStream in = process.getOutputStream()) {
                    in.write(stdin);
                } catch (IOException ignored) {
                }
            });
            Future<byte[]> stdout = io.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderr = io.submit(() -> process.getErrorStream().readAllBytes());
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new TranscodeException(Reason.TIMEOUT, "실행 시간 " + timeout.toSeconds() + "초 초과로 프로세스를 종료했습니다");
            }
            return new Result(process.exitValue(), stdout.get(), new String(stderr.get(), StandardCharsets.UTF_8).strip());
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new TranscodeException(Reason.FFMPEG_FAILED, "변환 대기 중 인터럽트", e);
        } catch (ExecutionException e) {
            throw new TranscodeException(Reason.FFMPEG_FAILED, "프로세스 출력 읽기 실패", e.getCause());
        }
    }

    // 자식부터 죽인다. 부모만 죽이면 자식이 stdout pipe 를 쥔 채 남아 출력 읽기가 끝나지 않는다
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // EXIF Orientation → 저장 픽셀을 화면 방향으로 돌리는 filter. transpose 0 = 주대각선 뒤집기, 1 = 시계 90도, 2 = 반시계 90도, 3 = 반대각선 뒤집기
    static String orientationFilter(int orientation) {
        return switch (orientation) {
            case 2 -> "hflip,";
            case 3 -> "hflip,vflip,";
            case 4 -> "vflip,";
            case 5 -> "transpose=0,";
            case 6 -> "transpose=1,";
            case 7 -> "transpose=3,";
            case 8 -> "transpose=2,";
            default -> "";
        };
    }

    // JPEG 품질(0~100, libjpeg 기준) → mjpeg qscale(2~31). libjpeg 는 품질 50 표준 양자화표를 (200 - 2×품질)% 로 줄이고,
    // FFmpeg mjpeg 는 MPEG-1 intra 행렬 × qscale / 8 을 쓴다. 두 표의 평균값(57.6, 33.0)을 맞추면 qscale ≈ (200 - 2×품질) × 0.14
    // ponytail: 평균만 맞춘 근사라 계수별 양자화는 libjpeg 와 다르고 qscale 이 정수라 품질 2~3 단위로만 구분된다. 정밀하게 맞춰야 하면 JPEG 인코딩만 libjpeg 계열 도구로 바꾼다
    static int mjpegQscale(int quality) {
        return (int) Math.max(2, Math.min(31, Math.round((200 - 2 * quality) * 0.14)));
    }

    private static int intOrZero(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    record Result(int exitCode, byte[] stdout, String stderr) {
    }
}
