package com.stagelog.photocard.domain;

/**
 * 네컷 프레임의 사진 칸 하나. 1080×3240 프레임 좌표(px)이며 프레임 SVG 의 투명 구멍과 겹쳐야 한다.
 */
public record SlotRect(int x, int y, int w, int h) {
}
