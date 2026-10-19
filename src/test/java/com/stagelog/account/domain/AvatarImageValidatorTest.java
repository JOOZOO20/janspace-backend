package com.stagelog.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvatarImageValidatorTest {

    static final int MAX = AvatarImageValidator.MAX_PX;

    @Test
    @DisplayName("PNG·JPEG 는 앞 바이트로 판정해 확장자·Content-Type 을 돌려주고, 긴 변이 딱 1024px 이면 통과한다")
    void png_and_jpeg_pass_up_to_max() throws IOException {
        assertThat(AvatarImageValidator.validate(image(MAX, 10, "png"))).isEqualTo(AvatarImageValidator.Format.PNG);
        assertThat(AvatarImageValidator.validate(image(10, MAX, "jpg"))).isEqualTo(AvatarImageValidator.Format.JPEG);
    }

    @Test
    @DisplayName("이미지가 아님·빈 바이트·null·5MB 초과·잘린 파일·긴 변 1025px 는 400 AVATAR_IMAGE_INVALID")
    void invalid_inputs_rejected() throws IOException {
        byte[][] cases = {
                "not image".getBytes(), new byte[0], null, new byte[(int) AvatarImageValidator.MAX_BYTES + 1],
                Arrays.copyOf(image(100, 100, "png"), 40), image(MAX + 1, 10, "png"), image(10, MAX + 1, "jpg")};
        for (byte[] bytes : cases) {
            assertThatThrownBy(() -> AvatarImageValidator.validate(bytes))
                    .isInstanceOf(BadRequestException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AVATAR_IMAGE_INVALID);
        }
    }

    @Test
    @DisplayName("헤더가 20000x20000 을 주장하는 작은 PNG 는 픽셀을 디코딩하기 전에 거부한다")
    void huge_declared_dimensions_rejected_before_decoding() throws IOException {
        byte[] png = image(10, 10, "png");
        // IHDR 의 width·height 는 시그니처(8) + 길이(4) + "IHDR"(4) 뒤 16·20 번째 바이트
        ByteBuffer.wrap(png).putInt(16, 20_000).putInt(20, 20_000);
        assertThatThrownBy(() -> AvatarImageValidator.validate(png))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AVATAR_IMAGE_INVALID);
    }

    // 실제 인코딩된 이미지 바이트. jpg 는 알파 채널이 없어야 ImageIO 가 쓴다
    static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage img = new BufferedImage(width, height, "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}
