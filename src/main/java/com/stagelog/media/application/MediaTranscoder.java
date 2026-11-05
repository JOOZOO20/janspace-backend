package com.stagelog.media.application;

import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.media.application.TranscodeException.Reason;
import com.stagelog.media.application.port.ImageTranscoderPort;
import com.stagelog.media.application.port.ImageTranscoderPort.Probe;
import com.stagelog.media.domain.ExifOrientation;
import com.stagelog.media.domain.RenditionFormat;
import com.stagelog.media.domain.RenditionProfile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 원본 1개를 rendition 1개(profile × format)로 바꾸는 변환 엔진. 1) 입구 검증 2) 회전·축소 크기 계산 3) 변환 4) 출력 검증 5) 출력 key.
 * 상태 전이(claim·완료)와 업로드는 Worker 가 맡고, 여기서는 bytes 만 다룬다.
 */
@Slf4j
@Service
public class MediaTranscoder {

    // 포스터·앨범 원본 상한. 네컷(3240px) 보다 크게 두고, 검증이 원본을 한 번 디코딩하므로 메모리 상한도 겸한다
    static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    static final int MAX_SOURCE_PX = 5000;
    private static final int SIZE_TOLERANCE_PX = 1;

    private final ImageTranscoderPort transcoder;

    public MediaTranscoder(ImageTranscoderPort transcoder) {
        this.transcoder = transcoder;
    }

    /** 원본에서 만들 rendition 형식 2개. 투명 영역이 있는 PNG 는 JPEG 대신 PNG 다. 검증 실패는 INVALID_SOURCE. */
    public List<RenditionFormat> formatsFor(byte[] source) {
        return inspect(source).transparent()
                ? List.of(RenditionFormat.WEBP, RenditionFormat.PNG)
                : List.of(RenditionFormat.WEBP, RenditionFormat.JPEG);
    }

    /**
     * rendition 1개를 만든다. 출력은 key 계산까지 끝난 상태이고 저장은 호출자가 한다.
     *
     * @throws TranscodeException INVALID_SOURCE 는 FFmpeg 실행 전에 나는 영구 실패, 나머지는 재시도 대상
     */
    public TranscodedRendition transcode(long assetId, byte[] source, RenditionProfile profile, RenditionFormat format) {
        // 1. 입구 검증. 실패하면 FFmpeg 를 띄우지 않는다
        Source src = inspect(source);

        // 2. EXIF 회전 후의 화면 크기 기준으로 긴 변을 맞춘다. 원본이 더 작으면 원본 크기 그대로
        boolean swap = ExifOrientation.swapsAxes(src.orientation());
        int displayWidth = swap ? src.height() : src.width();
        int displayHeight = swap ? src.width() : src.height();
        int longSide = Math.max(displayWidth, displayHeight);
        int width = displayWidth;
        int height = displayHeight;
        if (longSide > profile.longSide()) {
            width = Math.max(1, (int) Math.round((double) displayWidth * profile.longSide() / longSide));
            height = Math.max(1, (int) Math.round((double) displayHeight * profile.longSide() / longSide));
        }

        // 3. 변환 (외부 프로세스, 실행 시간 제한은 어댑터가 건다)
        byte[] output = transcoder.transcode(source, src.orientation(), width, height, format,
                profile.quality(format), src.transparent());

        // 4. 출력 검증: 비어 있지 않고, 디코딩되고, 해상도가 기대값 ±1px
        if (output.length == 0) {
            throw new TranscodeException(Reason.OUTPUT_INVALID, "출력이 비어 있습니다");
        }
        Probe probe = transcoder.probe(output);
        if (probe.decodedFrames() != 1
                || Math.abs(probe.width() - width) > SIZE_TOLERANCE_PX
                || Math.abs(probe.height() - height) > SIZE_TOLERANCE_PX) {
            throw new TranscodeException(Reason.OUTPUT_INVALID, "기대 " + width + "x" + height + ", 실제 " + probe);
        }
        if (output.length > source.length) {
            log.warn("[미디어 변환 - 출력이 원본보다 큼] assetId={}, profile={}, format={}, 원본bytes={}, 출력bytes={}",
                    assetId, profile.key(), format.extension(), source.length, output.length);
        }

        // 5. 출력 key: 내용이 바뀌면 key 도 바뀌어 CDN 캐시와 겹치지 않는다
        String key = "media/" + assetId + "/" + profile.key() + "-" + sha256(output).substring(0, 8) + "." + format.extension();
        return new TranscodedRendition(profile, format, key, probe.width(), probe.height(), output);
    }

    private Source inspect(byte[] source) {
        ImageValidator.Image image;
        try {
            image = ImageValidator.inspect(source, MAX_SOURCE_BYTES, MAX_SOURCE_PX);
        } catch (BadRequestException e) {
            throw new TranscodeException(Reason.INVALID_SOURCE, e.getMessage(), e);
        }
        boolean jpeg = image.format() == ImageValidator.Format.JPEG;
        return new Source(image.width(), image.height(),
                jpeg ? ExifOrientation.of(source) : 1,
                !jpeg && hasTransparentPixel(source));
    }

    // 알파 채널이 있어도 전부 불투명이면 JPEG 로 만든다 (canvas export PNG 는 대개 RGBA 이지만 불투명)
    private static boolean hasTransparentPixel(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (!image.getColorModel().hasAlpha()) {
                return false;
            }
            int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, row.length, 1, row, 0, row.length);
                for (int argb : row) {
                    if (argb >>> 24 != 0xFF) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }

    private record Source(int width, int height, int orientation, boolean transparent) {
    }
}
