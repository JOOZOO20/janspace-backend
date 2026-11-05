package com.stagelog.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stagelog.media.application.MediaTranscoder;
import com.stagelog.media.application.TranscodeException;
import com.stagelog.media.application.TranscodeException.Reason;
import com.stagelog.media.application.TranscodedRendition;
import com.stagelog.media.application.port.ImageTranscoderPort;
import com.stagelog.media.domain.ExifOrientation;
import com.stagelog.media.domain.RenditionFormat;
import com.stagelog.media.domain.RenditionProfile;
import com.stagelog.support.FfmpegContainer;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 변환 엔진(MediaTranscoder + FFmpeg 어댑터) 통합 테스트. 저장소 Dockerfile 의 runtime stage(Worker 와 같은 FFmpeg, FfmpegContainer)를 띄우고
 * docker exec -i prefix 로 호출하므로 운영과 같은 FFmpeg·같은 pipe 경로를 검증한다.
 */
@Tag("integration")
class FfmpegTranscodeIntegrationTest {

    static final String EXIF_MARKER = "JAN05-EXIF-MARKER";
    static final AtomicInteger FFMPEG_CALLS = new AtomicInteger();

    static FfmpegImageTranscoder ffmpeg;
    static MediaTranscoder engine;

    @BeforeAll
    static void start() {
        FfmpegContainer.start();
        ffmpeg = new FfmpegImageTranscoder(exec("ffmpeg"), exec("ffprobe"), Duration.ofSeconds(60));
        // WebP rendition 은 libwebp encoder 가 있어야 만들 수 있다
        String encoders = new String(ffmpeg.run(exec("ffmpeg", "-hide_banner", "-encoders"), new byte[0]).stdout(), StandardCharsets.UTF_8);
        assertThat(encoders).contains("libwebp");

        // FFmpeg 실행 횟수를 세어 "변환 전에" 거부됐는지 확인한다
        engine = new MediaTranscoder(new ImageTranscoderPort() {
            @Override
            public byte[] transcode(byte[] source, int orientation, int width, int height, RenditionFormat format, int quality, boolean alpha) {
                FFMPEG_CALLS.incrementAndGet();
                return ffmpeg.transcode(source, orientation, width, height, format, quality, alpha);
            }

            @Override
            public Probe probe(byte[] image) {
                return ffmpeg.probe(image);
            }
        });
    }

    @ParameterizedTest(name = "Orientation {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("EXIF Orientation 1~8 원본은 화면 방향으로 회전되고, 출력에 EXIF·GPS 가 없다")
    void exif_orientation_applied_and_metadata_stripped(int orientation) throws IOException {
        // 화면 방향 480x240 (사분면 색이 모두 다름) → 저장 픽셀로 되돌리고 EXIF(Orientation, 설명 표식, GPS) 를 붙인다
        BufferedImage display = quadrants(480, 240);
        byte[] source = withExif(jpeg(stored(display, orientation)), orientation);
        assertThat(ExifOrientation.of(source)).isEqualTo(orientation);
        assertThat(ascii(source)).contains("Exif", EXIF_MARKER);

        for (RenditionFormat format : List.of(RenditionFormat.JPEG, RenditionFormat.WEBP)) {
            TranscodedRendition out = engine.transcode(1L, source, RenditionProfile.THUMB, format);

            // 1. 긴 변 320 으로 줄고 가로·세로가 화면 방향이다
            assertThat(out.width()).isEqualTo(320);
            assertThat(out.height()).isEqualTo(160);
            BufferedImage decoded = decode(out.bytes());
            assertThat(decoded.getWidth()).isEqualTo(320);
            assertThat(decoded.getHeight()).isEqualTo(160);

            // 2. 사분면 색이 화면 방향 그대로다
            assertColor(decoded, 80, 40, Color.RED);
            assertColor(decoded, 240, 40, Color.GREEN);
            assertColor(decoded, 80, 120, Color.BLUE);
            assertColor(decoded, 240, 120, Color.WHITE);

            // 3. EXIF 블록, 설명 표식, GPS 가 남지 않는다
            assertThat(ascii(out.bytes())).doesNotContain("Exif", "EXIF", EXIF_MARKER, "http://ns.adobe.com/xap");
            assertThat(indexOf(out.bytes(), GPS_LATITUDE)).isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("잘린 JPEG·확장자 위장·초대형 해상도는 FFmpeg 실행 전에 INVALID_SOURCE (재시도 안 함)")
    void invalid_sources_rejected_before_ffmpeg() throws IOException {
        byte[] valid = jpeg(quadrants(400, 300));
        byte[] truncated = Arrays.copyOf(valid, valid.length / 2);
        byte[] gifNamedJpg = encode(quadrants(40, 30), "gif");
        byte[] svgNamedJpg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        byte[] hugeHeader = withPngSize(encode(quadrants(16, 16), "png"), 100_000, 100_000);

        int before = FFMPEG_CALLS.get();
        for (byte[] source : List.of(truncated, gifNamedJpg, svgNamedJpg, hugeHeader)) {
            assertThatThrownBy(() -> engine.transcode(1L, source, RenditionProfile.LIST, RenditionFormat.WEBP))
                    .isInstanceOfSatisfying(TranscodeException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.INVALID_SOURCE);
                        assertThat(e.retryable()).isFalse();
                    });
            assertThatThrownBy(() -> engine.formatsFor(source))
                    .isInstanceOfSatisfying(TranscodeException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_SOURCE));
        }
        assertThat(FFMPEG_CALLS.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("원본이 목표 긴 변보다 작으면 확대하지 않는다")
    void smaller_source_not_upscaled() throws IOException {
        byte[] source = jpeg(quadrants(200, 100));
        for (RenditionFormat format : List.of(RenditionFormat.WEBP, RenditionFormat.JPEG)) {
            TranscodedRendition out = engine.transcode(7L, source, RenditionProfile.DETAIL, format);
            assertThat(out.width()).isEqualTo(200);
            assertThat(out.height()).isEqualTo(100);
            assertThat(decode(out.bytes()).getWidth()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("투명 영역이 있는 PNG 는 JPEG 대신 PNG 를 만들고 WebP·PNG 모두 투명도를 유지한다")
    void transparent_png_falls_back_to_png() throws IOException {
        BufferedImage half = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 200; y++) {
            for (int x = 150; x < 300; x++) {
                half.setRGB(x, y, 0xFF2266AA);
            }
        }
        byte[] transparent = encode(half, "png");
        assertThat(engine.formatsFor(transparent)).containsExactly(RenditionFormat.WEBP, RenditionFormat.PNG);

        for (RenditionFormat format : List.of(RenditionFormat.WEBP, RenditionFormat.PNG)) {
            BufferedImage decoded = decode(engine.transcode(3L, transparent, RenditionProfile.THUMB, format).bytes());
            assertThat(decoded.getRGB(40, 100) >>> 24).as("투명 영역").isLessThan(16);
            assertThat(decoded.getRGB(260, 100) >>> 24).as("불투명 영역").isGreaterThan(240);
        }

        // 알파 채널만 있고 전부 불투명한 PNG(canvas export), JPEG 는 그대로 WebP + JPEG
        BufferedImage opaque = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
        opaque.createGraphics().fillRect(0, 0, 300, 200);
        assertThat(engine.formatsFor(encode(opaque, "png"))).containsExactly(RenditionFormat.WEBP, RenditionFormat.JPEG);
        assertThat(engine.formatsFor(jpeg(quadrants(300, 200)))).containsExactly(RenditionFormat.WEBP, RenditionFormat.JPEG);
    }

    @Test
    @DisplayName("출력 key 는 media/{assetId}/{profile}-{출력 sha256 앞 8자}.{ext}")
    void output_key_uses_output_hash() throws Exception {
        byte[] source = jpeg(quadrants(1600, 1000));
        for (RenditionProfile profile : RenditionProfile.values()) {
            TranscodedRendition out = engine.transcode(42L, source, profile, RenditionFormat.JPEG);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.bytes()));
            assertThat(out.key()).isEqualTo("media/42/" + profile.key() + "-" + sha.substring(0, 8) + ".jpg");
            assertThat(Math.max(out.width(), out.height())).isEqualTo(profile.longSide());
        }
    }

    @Test
    @DisplayName("실행 시간 제한을 넘기면 프로세스를 강제 종료하고 TIMEOUT (재시도 대상)")
    void timeout_kills_process() throws IOException {
        // FFmpeg 대신 끝나지 않는 명령을 같은 prefix 자리에 넣는다. 인자는 sh 의 위치 인자로 무시된다
        FfmpegImageTranscoder hanging = new FfmpegImageTranscoder(
                List.of("sh", "-c", "sleep 30", "sh"), List.of("ffprobe"), Duration.ofMillis(500));
        byte[] source = jpeg(quadrants(100, 100));

        long started = System.nanoTime();
        assertThatThrownBy(() -> hanging.transcode(source, 1, 100, 100, RenditionFormat.WEBP, 80, false))
                .isInstanceOfSatisfying(TranscodeException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
                    assertThat(e.retryable()).isTrue();
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(sleepDescendants()).as("남은 sleep 프로세스").isZero();
    }

    @Test
    @DisplayName("출력 해상도가 기대값 ±1px 를 벗어나거나 디코딩되지 않으면 OUTPUT_INVALID (재시도 대상)")
    void output_verified() throws IOException {
        byte[] source = jpeg(quadrants(800, 400));
        MediaTranscoder wrongSize = new MediaTranscoder(stub(new ImageTranscoderPort.Probe("webp", 320, 158, 1)));
        MediaTranscoder undecodable = new MediaTranscoder(stub(new ImageTranscoderPort.Probe("webp", 0, 0, 0)));
        MediaTranscoder offByOne = new MediaTranscoder(stub(new ImageTranscoderPort.Probe("webp", 321, 159, 1)));

        for (MediaTranscoder bad : List.of(wrongSize, undecodable)) {
            assertThatThrownBy(() -> bad.transcode(1L, source, RenditionProfile.THUMB, RenditionFormat.WEBP))
                    .isInstanceOfSatisfying(TranscodeException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.OUTPUT_INVALID);
                        assertThat(e.retryable()).isTrue();
                    });
        }
        assertThat(offByOne.transcode(1L, source, RenditionProfile.THUMB, RenditionFormat.WEBP).width()).isEqualTo(321);

        // 실제 FFmpeg 출력을 자르면 ffprobe 가 디코딩하지 못한다
        byte[] webp = ffmpeg.transcode(source, 1, 320, 160, RenditionFormat.WEBP, 80, false);
        assertThat(ffmpeg.probe(Arrays.copyOf(webp, webp.length / 3)).decodedFrames()).isZero();
    }

    static ImageTranscoderPort stub(ImageTranscoderPort.Probe probe) {
        return new ImageTranscoderPort() {
            @Override
            public byte[] transcode(byte[] source, int orientation, int width, int height, RenditionFormat format, int quality, boolean alpha) {
                return new byte[] {1, 2, 3};
            }

            @Override
            public Probe probe(byte[] image) {
                return probe;
            }
        };
    }

    static List<String> exec(String... command) {
        return FfmpegContainer.exec(command);
    }

    // 출력 디코딩. JDK ImageIO 에 WebP reader 가 없어 WebP 는 컨테이너의 FFmpeg 로 PNG 로 바꿔 읽는다
    static BufferedImage decode(byte[] image) throws IOException {
        byte[] readable = image;
        if (ascii(image).startsWith("RIFF")) {
            readable = ffmpeg.run(exec("ffmpeg", "-v", "error", "-i", "pipe:0", "-c:v", "png", "-pix_fmt", "rgba", "-f", "image2pipe", "pipe:1"), image).stdout();
        }
        return ImageIO.read(new ByteArrayInputStream(readable));
    }

    static void assertColor(BufferedImage image, int x, int y, Color expected) {
        Color actual = new Color(image.getRGB(x, y));
        assertThat(Math.abs(actual.getRed() - expected.getRed())).as("(%d,%d) R %s", x, y, actual).isLessThan(60);
        assertThat(Math.abs(actual.getGreen() - expected.getGreen())).as("(%d,%d) G %s", x, y, actual).isLessThan(60);
        assertThat(Math.abs(actual.getBlue() - expected.getBlue())).as("(%d,%d) B %s", x, y, actual).isLessThan(60);
    }

    // 좌상 빨강, 우상 초록, 좌하 파랑, 우하 흰색
    static BufferedImage quadrants(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean right = x >= width / 2;
                boolean bottom = y >= height / 2;
                Color c = bottom ? (right ? Color.WHITE : Color.BLUE) : (right ? Color.GREEN : Color.RED);
                image.setRGB(x, y, c.getRGB());
            }
        }
        return image;
    }

    // EXIF 정의대로 화면 = T(저장) 인 저장 픽셀을 만든다. 저장 (x, y) 가 화면의 어느 좌표인지로 채운다
    static BufferedImage stored(BufferedImage display, int orientation) {
        boolean swap = orientation >= 5;
        int sw = swap ? display.getHeight() : display.getWidth();
        int sh = swap ? display.getWidth() : display.getHeight();
        BufferedImage stored = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int[] d = switch (orientation) {
                    case 2 -> new int[] {sw - 1 - x, y};          // 좌우 반전
                    case 3 -> new int[] {sw - 1 - x, sh - 1 - y}; // 180도
                    case 4 -> new int[] {x, sh - 1 - y};          // 상하 반전
                    case 5 -> new int[] {y, x};                   // 좌우 반전 후 270도 (주대각선)
                    case 6 -> new int[] {sh - 1 - y, x};          // 시계 90도
                    case 7 -> new int[] {sh - 1 - y, sw - 1 - x}; // 좌우 반전 후 90도 (반대각선)
                    case 8 -> new int[] {y, sw - 1 - x};          // 반시계 90도
                    default -> new int[] {x, y};
                };
                stored.setRGB(x, y, display.getRGB(d[0], d[1]));
            }
        }
        return stored;
    }

    static final byte[] GPS_LATITUDE = {0, 0, 0, 37, 0, 0, 0, 1, 0, 0, 0, 33, 0, 0, 0, 1};

    // SOI 바로 뒤에 APP1 EXIF(big endian) 를 넣는다. IFD0: ImageDescription(표식), Orientation, GPS IFD 포인터 / GPS IFD: 위도 N 37°33'
    static byte[] withExif(byte[] jpeg, int orientation) {
        byte[] description = (EXIF_MARKER + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 42 + description.length + 30 + 24);
        tiff.put(new byte[] {'M', 'M', 0, 42}).putInt(8);
        int descriptionAt = 8 + 42;
        int gpsAt = descriptionAt + description.length;
        int latitudeAt = gpsAt + 30;
        tiff.putShort((short) 3);
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(description.length).putInt(descriptionAt);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsAt);
        tiff.putInt(0);
        tiff.put(description);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x0001).putShort((short) 2).putInt(2).put(new byte[] {'N', 0, 0, 0});
        tiff.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(latitudeAt);
        tiff.putInt(0);
        tiff.put(GPS_LATITUDE).put(new byte[8]);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        int length = 2 + 6 + tiff.capacity();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length, 'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff.array());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    // IHDR 의 가로·세로만 바꾸고 CRC 를 다시 계산한다 (작은 파일이 거대한 크기를 선언하는 decompression bomb)
    static byte[] withPngSize(byte[] png, int width, int height) {
        byte[] out = png.clone();
        ByteBuffer.wrap(out).putInt(16, width).putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(out, 12, 17);
        ByteBuffer.wrap(out).putInt(29, (int) crc.getValue());
        return out;
    }

    static byte[] jpeg(BufferedImage image) throws IOException {
        return encode(image, "jpg");
    }

    static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }

    static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        return ascii(haystack).indexOf(ascii(needle));
    }

    // 테스트 JVM 의 자식 중 아직 살아 있는 sleep. 강제 종료 직후 회수까지 잠깐 기다린다
    static long sleepDescendants() {
        for (int i = 0; i < 20; i++) {
            long alive = ProcessHandle.current().descendants()
                    .filter(p -> p.info().command().map(c -> c.endsWith("sleep")).orElse(false))
                    .count();
            if (alive == 0) {
                return 0;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return alive;
            }
        }
        return ProcessHandle.current().descendants().filter(p -> p.info().command().map(c -> c.endsWith("sleep")).orElse(false)).count();
    }
}
