package com.stagelog.media.application;

import com.stagelog.media.domain.RenditionFormat;
import com.stagelog.media.domain.RenditionProfile;

/**
 * 출력 검증을 통과한 rendition. key 는 media/{assetId}/{profile}-{출력 sha256 앞 8자}.{ext} 이다.
 */
public record TranscodedRendition(RenditionProfile profile, RenditionFormat format, String key,
                                  int width, int height, byte[] bytes) {
}
