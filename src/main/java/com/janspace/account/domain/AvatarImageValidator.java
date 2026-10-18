package com.janspace.account.domain;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * 프로필 사진 검증: 앞 바이트로 PNG/JPEG 판정, 5MB 이하, 긴 변 1024px 이하, 실제로 디코딩되는지. 줄이지 않고 거부한다
 * (리사이즈는 클라이언트 몫). 실패는 모두 400 AVATAR_IMAGE_INVALID.
 */
public final class AvatarImageValidator {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final int MAX_PX = 1024;

    private AvatarImageValidator() {
    }

    // 확장자와 Content-Type. 저장 키와 S3 메타데이터에 쓴다
    public record Format(String extension, String contentType) {
        public static final Format PNG = new Format("png", "image/png");
        public static final Format JPEG = new Format("jpg", "image/jpeg");
    }

    public static Format validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw invalid();
        }
        Format format = formatOf(bytes);
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                // 크기를 헤더에서 먼저 본다: 작은 파일이 거대한 해상도로 풀리는 이미지를 메모리에 디코딩하지 않기 위해서
                if (Math.max(reader.getWidth(0), reader.getHeight(0)) > MAX_PX) {
                    throw invalid();
                }
                // 시그니처는 맞지만 디코딩이 안 되는(잘린) 파일도 거부한다
                reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // 신뢰할 수 없는 바이트라 디코더의 비검사 예외(손상 데이터)도 500 이 아니라 400 으로 돌린다
            throw invalid();
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
        throw invalid();
    }

    private static BadRequestException invalid() {
        return new BadRequestException(ErrorCode.AVATAR_IMAGE_INVALID);
    }
}
