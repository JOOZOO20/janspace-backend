package com.janspace.photocard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageValidatorTest {

    static final int MAX_PX = 1350;

    @Test
    @DisplayName("PNG·JPEG 는 앞 바이트로 판정해 확장자·Content-Type 을 돌려준다")
    void png_and_jpeg_pass() throws IOException {
        assertThat(ImageValidator.validate(image(100, 80, "png"), MAX_PX)).isEqualTo(ImageValidator.Format.PNG);
        assertThat(ImageValidator.validate(image(80, 100, "jpg"), MAX_PX)).isEqualTo(ImageValidator.Format.JPEG);
    }

    @Test
    @DisplayName("이미지가 아니거나 비어 있거나 5MB 를 넘으면 400 PHOTOCARD_IMAGE_INVALID")
    void non_image_rejected() {
        for (byte[] bytes : new byte[][] {"not image".getBytes(), new byte[0], null, new byte[(int) ImageValidator.MAX_BYTES + 1]}) {
            assertThatThrownBy(() -> ImageValidator.validate(bytes, MAX_PX))
                    .isInstanceOf(BadRequestException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.PHOTOCARD_IMAGE_INVALID);
        }
    }

    @Test
    @DisplayName("시그니처는 맞지만 디코딩이 안 되는(잘린) 파일은 거부한다")
    void truncated_rejected() throws IOException {
        byte[] truncated = Arrays.copyOf(image(100, 100, "png"), 40);
        assertThatThrownBy(() -> ImageValidator.validate(truncated, MAX_PX)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("긴 변이 maxPx 를 넘으면 거부하고, 딱 maxPx 는 통과한다")
    void longest_side_boundary() throws IOException {
        assertThat(ImageValidator.validate(image(MAX_PX, 10, "png"), MAX_PX)).isEqualTo(ImageValidator.Format.PNG);
        assertThat(ImageValidator.validate(image(10, MAX_PX, "jpg"), MAX_PX)).isEqualTo(ImageValidator.Format.JPEG);
        assertThatThrownBy(() -> ImageValidator.validate(image(MAX_PX + 1, 10, "png"), MAX_PX))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PHOTOCARD_IMAGE_INVALID);
        assertThatThrownBy(() -> ImageValidator.validate(image(10, MAX_PX + 1, "jpg"), MAX_PX))
                .isInstanceOf(BadRequestException.class);
    }

    // 실제 인코딩된 이미지 바이트. jpg 는 알파 채널이 없어야 ImageIO 가 쓴다
    static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage img = new BufferedImage(width, height, "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}
