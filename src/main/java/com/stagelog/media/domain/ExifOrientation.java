package com.stagelog.media.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * JPEG EXIF Orientation(1~8). 저장된 픽셀을 이 값대로 돌려야 화면 방향이 된다.
 * 5~8 은 가로·세로가 바뀐다. 태그가 없거나 구조가 깨져 있으면 1(그대로)이다.
 */
public final class ExifOrientation {

    private static final int ORIENTATION_TAG = 0x0112;

    private ExifOrientation() {
    }

    // ponytail: JPEG APP1 만 본다. PNG eXIf 청크는 canvas export 원본에 없어 1 로 둔다
    public static int of(byte[] jpeg) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(jpeg);
            if (buf.getShort(0) != (short) 0xFFD8) {
                return 1;
            }
            // 1. SOS(FFDA) 전까지 세그먼트를 따라가며 "Exif\0\0" APP1 을 찾는다
            int pos = 2;
            while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
                int marker = jpeg[pos + 1] & 0xFF;
                int length = buf.getShort(pos + 2) & 0xFFFF;
                if (marker == 0xDA) {
                    return 1;
                }
                if (marker == 0xE1 && length >= 8 && buf.getInt(pos + 4) == 0x45786966 && buf.getShort(pos + 8) == 0) {
                    return orientationInTiff(buf, pos + 10);
                }
                pos += 2 + length;
            }
            return 1;
        } catch (IndexOutOfBoundsException e) {
            return 1;
        }
    }

    public static boolean swapsAxes(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    // 2. TIFF 헤더(II/MM, 42, IFD0 오프셋) 다음 IFD0 항목(12바이트)에서 0x0112 SHORT 를 읽는다
    private static int orientationInTiff(ByteBuffer jpeg, int tiff) {
        ByteBuffer buf = jpeg.duplicate().order(jpeg.getShort(tiff) == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int ifd = tiff + buf.getInt(tiff + 4);
        int count = buf.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if ((buf.getShort(entry) & 0xFFFF) == ORIENTATION_TAG) {
                int value = buf.getShort(entry + 8) & 0xFFFF;
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }
}
