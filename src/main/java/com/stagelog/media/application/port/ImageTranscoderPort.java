package com.stagelog.media.application.port;

import com.stagelog.media.domain.RenditionFormat;

/**
 * 이미지 변환 엔진. 구현은 FFmpeg CLI (infrastructure.FfmpegImageTranscoder). 입출력은 bytes 이고 파일을 쓰지 않는다.
 * 실패는 TranscodeException(TIMEOUT, FFMPEG_FAILED) 으로 던진다.
 */
public interface ImageTranscoderPort {

    /**
     * 원본을 orientation 대로 회전한 뒤 width×height 로 줄여 format 으로 인코딩한다. 메타데이터(EXIF·GPS 포함)는 남기지 않는다.
     *
     * @param quality 0~100 (PNG 는 무시)
     * @param alpha   투명도를 유지할지. JPEG 는 무시
     */
    byte[] transcode(byte[] source, int orientation, int width, int height, RenditionFormat format, int quality, boolean alpha);

    // 첫 번째 영상 stream 을 실제로 디코딩해 본 결과
    Probe probe(byte[] image);

    record Probe(String codec, int width, int height, int decodedFrames) {
    }
}
