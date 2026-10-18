package com.janspace.photocard.domain;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 포토카드·스티커 이미지 검증: 앞 바이트로 PNG/JPEG 판정, 5MB 이하, 디코딩해 긴 변이 maxPx 이하.
 * 실패는 모두 400 PHOTOCARD_IMAGE_INVALID.
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

    public static Format validate(byte[] bytes, int maxPx) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID);
        }
        Format format = formatOf(bytes);
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID);
        }
        // 시그니처는 맞지만 디코딩이 안 되는(잘린) 파일도 거부한다
        if (image == null || Math.max(image.getWidth(), image.getHeight()) > maxPx) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID, "이미지 긴 변은 " + maxPx + "px 이하여야 합니다.");
        }
        return format;
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
