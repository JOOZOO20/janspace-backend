package com.stagelog.media.application;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 화면별 이미지 URL. 응답 DTO 가 그대로 쓰는 공개 계약이다(기능마다 같은 모양을 복제하지 않으려고 media 가 소유한다).
 * READY 가 아닌 rendition 자리에는 원본 URL 이 들어가므로 모든 자리가 항상 유효한 URL 이다.
 */
@Schema(description = "화면별 이미지 URL (thumb 긴 변 320px, list 720px, detail 1440px). 변환이 끝나지 않은 자리는 원본 URL",
        example = "{\"thumb\":{\"webp\":\"https://cdn.example/media/1/thumb-1a2b3c4d.webp\",\"jpeg\":\"https://cdn.example/media/1/thumb-5e6f7a8b.jpg\"},"
                + "\"list\":{\"webp\":\"...\",\"jpeg\":\"...\"},\"detail\":{\"webp\":\"...\",\"jpeg\":\"...\"}}")
public record ImageSet(Variant thumb, Variant list, Variant detail) {

    @Schema(description = "같은 크기의 두 형식. jpeg 는 WebP 를 못 쓰는 클라이언트용이고, 투명 영역이 있는 원본은 PNG URL 이다")
    public record Variant(
            @Schema(description = "WebP URL", requiredMode = Schema.RequiredMode.REQUIRED) String webp,
            @Schema(description = "JPEG URL (투명 원본은 PNG)", requiredMode = Schema.RequiredMode.REQUIRED) String jpeg) {
    }

    // 모든 자리가 원본
    public static ImageSet original(String url) {
        Variant v = new Variant(url, url);
        return new ImageSet(v, v, v);
    }
}
