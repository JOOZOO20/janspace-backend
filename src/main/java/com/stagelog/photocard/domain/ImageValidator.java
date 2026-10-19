package com.stagelog.photocard.domain;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 포토카드·스티커 이미지 검증: 앞 바이트로 PNG/JPEG 판정, 바이트 상한, 긴 변 상한, 실제 디코딩.
 * 실패는 모두 400 PHOTOCARD_IMAGE_INVALID 이고, 메시지는 호출자가 넘긴 상한(카드 5MB·1350px, 네컷 9MB·3240px 등)으로 만든다.
 */
public final class ImageValidator {

    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private ImageValidator() {
    }

    /** 확장자와 Content-Type. 저장 키와 S3 메타데이터에 쓴다. */
    public record Format(String extension, String contentType) {
        public static final Format PNG = new Format("png", "image/png");
        public static final Format JPEG = new Format("jpg", "image/jpeg");
    }

    /** 검증을 통과한 이미지의 형식과 크기. */
    public record Image(Format format, int width, int height) {
    }

    public static Format validate(byte[] bytes, int maxPx) {
        return inspect(bytes, MAX_BYTES, maxPx).format();
    }

    public static Image inspect(byte[] bytes, long maxBytes, int maxPx) {
        String rule = "이미지는 " + maxBytes / (1024 * 1024) + "MB 이하, 긴 변 " + maxPx + "px 이하의 PNG 또는 JPEG 여야 합니다.";
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID, rule);
        }
        if (bytes.length > maxBytes) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID, "이미지는 " + maxBytes / (1024 * 1024) + "MB 이하여야 합니다.");
        }
        Format format;
        try {
            format = formatOf(bytes);
        } catch (BadRequestException e) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID, rule);
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID, rule);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                // 1. 헤더의 크기부터 본다. 작은 파일이 거대한 크기를 선언하면 디코딩이 메모리를 다 쓰므로 먼저 거른다
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (Math.max(width, height) > maxPx) {
                    throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID, "이미지 긴 변은 " + maxPx + "px 이하여야 합니다.");
                }
                // 2. 시그니처는 맞지만 디코딩이 안 되는(잘린) 파일도 거부한다
                reader.read(0);
                return new Image(format, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID, rule);
        }
    }

    // PNG 시그니처 89 50 4E 47, JPEG 시그니처 FF D8 FF
    static Format formatOf(byte[] bytes) {
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return Format.PNG;
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Format.JPEG;
        }
        throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID);
    }
}
