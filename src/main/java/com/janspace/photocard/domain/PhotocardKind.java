package com.janspace.photocard.domain;

/**
 * 포토카드 종류. 네컷도 포토카드의 한 종류라 바인더에 함께 꽂히고 월 제한도 함께 센다.
 * 바인더 격자가 4:5 칸에 네컷(1:3)을 2칸 높이로 섞어 놓으므로 이미지 비율이 종류와 맞아야 한다.
 */
public enum PhotocardKind {

    // 1080×1350 기준. 사진 위주 PNG 라 5MB 안에 든다
    CARD(4, 5, 5L * 1024 * 1024),
    // 1080×3240 기준. 픽셀이 카드의 2.4배라 바이트 상한을 multipart 요청 상한(10MB) 안에서 넓힌다
    FOURCUT(1, 3, 9L * 1024 * 1024);

    private final int ratioW;
    private final int ratioH;
    private final long maxBytes;

    PhotocardKind(int ratioW, int ratioH, long maxBytes) {
        this.ratioW = ratioW;
        this.ratioH = ratioH;
        this.maxBytes = maxBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    // 캔버스를 내보낼 때의 반올림을 감안해 높이 1px 오차까지 허용한다
    public boolean matchesRatio(int width, int height) {
        return Math.abs((long) height * ratioW - (long) width * ratioH) <= ratioW;
    }

    public String ratioLabel() {
        return ratioW + ":" + ratioH;
    }
}
