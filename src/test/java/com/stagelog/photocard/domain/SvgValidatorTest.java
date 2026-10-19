package com.stagelog.photocard.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SvgValidatorTest {

    static final String OPEN = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 1080 3240\">";

    @Test
    @DisplayName("프론트 프레임과 같은 모양(mask·rect·text·style·#참조·/경로·data:image)은 통과한다")
    void plain_frame_passes() {
        String svg = OPEN + "<defs><mask id=\"m\"><rect width=\"1080\" height=\"3240\" fill=\"#fff\"/></mask></defs>"
                + "<style>text { font-family: monospace; }</style>"
                + "<rect width=\"1080\" height=\"3240\" fill=\"#1B2A4A\" mask=\"url(#m)\"/>"
                + "<use href=\"#m\"/><image href=\"/assets/svg/monkey-casual.svg\" width=\"10\" height=\"10\"/>"
                + "<image xlink:href=\"data:image/png;base64,iVBORw0KGgo=\" width=\"1\" height=\"1\"/>"
                + "<text x=\"540\" y=\"3000\">JANNABI</text></svg>";
        assertThatCode(() -> SvgValidator.validate(bytes(svg))).doesNotThrowAnyException();
        // 목업 크기(180×540)처럼 1:3 이면 다른 단위도 된다
        assertThatCode(() -> SvgValidator.validate(bytes("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0,0,180,540\"/>")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("스크립트·이벤트 속성·javascript:·HTML 삽입·외부 링크·DOCTYPE·비 SVG·비율 오류는 400 FRAME_SVG_INVALID")
    @ValueSource(strings = {
            OPEN + "<script>alert(1)</script></svg>",
            OPEN + "<SCRIPT>alert(1)</SCRIPT></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1080 3240\" onload=\"alert(1)\"/>",
            OPEN + "<rect ONCLICK=\"alert(1)\"/></svg>",
            OPEN + "<a href=\"javascript:alert(1)\"><rect/></a></svg>",
            OPEN + "<a xlink:href=\"java&#x09;script:alert(1)\"><rect/></a></svg>",
            OPEN + "<animate attributeName=\"href\" values=\"javascript:alert(1)\"/></svg>",
            OPEN + "<foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\">x</div></foreignObject></svg>",
            OPEN + "<image href=\"https://tracker.example/x.png\"/></svg>",
            OPEN + "<image href=\"//tracker.example/x.png\"/></svg>",
            OPEN + "<style>@import url(https://evil.example/x.css);</style></svg>",
            "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + OPEN + "<text>&x;</text></svg>",
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"/>",
            "<svg viewBox=\"0 0 1080 3240\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1080 1350\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1080 3240\">",
            "not xml"
    })
    void dangerous_or_wrong_svg_rejected(String svg) {
        assertThatThrownBy(() -> SvgValidator.validate(bytes(svg)))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FRAME_SVG_INVALID);
    }

    @Test
    @DisplayName("비었거나 2MB 를 넘으면 거부한다")
    void size_limits() {
        assertThatThrownBy(() -> SvgValidator.validate(new byte[0])).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> SvgValidator.validate(null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> SvgValidator.validate(new byte[(int) SvgValidator.MAX_BYTES + 1])).isInstanceOf(BadRequestException.class);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
