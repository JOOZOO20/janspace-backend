package com.stagelog.media.domain;

/**
 * 화면별 rendition 규격. 긴 변 기준이고 원본이 더 작으면 확대하지 않는다.
 * 값은 명세서 제안값이다(화면 기획과 확정 전). 품질은 0~100 이고 PNG 는 무손실이라 품질이 없다.
 */
public enum RenditionProfile {

    THUMB("thumb", 320, 75, 80),
    LIST("list", 720, 80, 82),
    DETAIL("detail", 1440, 85, 85);

    private final String key;
    private final int longSide;
    private final int webpQuality;
    private final int jpegQuality;

    RenditionProfile(String key, int longSide, int webpQuality, int jpegQuality) {
        this.key = key;
        this.longSide = longSide;
        this.webpQuality = webpQuality;
        this.jpegQuality = jpegQuality;
    }

    // 출력 key 와 DB profile 컬럼 값
    public String key() {
        return key;
    }

    public static RenditionProfile ofKey(String key) {
        for (RenditionProfile profile : values()) {
            if (profile.key.equals(key)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("알 수 없는 rendition profile: " + key);
    }

    public int longSide() {
        return longSide;
    }

    public int quality(RenditionFormat format) {
        return switch (format) {
            case WEBP -> webpQuality;
            case JPEG -> jpegQuality;
            case PNG -> 0;
        };
    }
}
