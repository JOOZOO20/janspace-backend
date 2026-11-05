package com.stagelog.media.domain;

/**
 * rendition 출력 형식. PNG 는 투명 영역이 있는 원본에서 JPEG 대신 만드는 fallback 이다.
 */
public enum RenditionFormat {

    WEBP("webp", "image/webp"),
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png");

    private final String extension;
    private final String contentType;

    RenditionFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }
}
