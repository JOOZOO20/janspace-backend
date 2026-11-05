package com.stagelog.event.application;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 행사 포스터 S3 key 규칙. 버전 key = events/{eventId}/poster/{sha256 앞 16자}.{ext} (뒤쪽은 저장소가 붙인다),
 * 고정 key(버전 key 이전, jan.storage.versioned-key.enabled=false) = events/{eventId}/poster.{ext}.
 */
public final class PosterKeys {

    private static final Pattern OWNED = Pattern.compile("^events/(\\d+)/poster[./].+$");
    private static final Pattern FIXED = Pattern.compile("^events/\\d+/poster\\.[a-z0-9]+$");

    private PosterKeys() {
    }

    public static String versionedPrefix(Long eventId) {
        return "events/" + eventId + "/poster/";
    }

    public static String fixedKey(Long eventId, String extension) {
        return "events/" + eventId + "/poster." + extension;
    }

    public static boolean isFixed(String key) {
        return key != null && FIXED.matcher(key).matches();
    }

    // 이 행사의 포스터로 저장한 key 인가 (고정·버전 모두)
    public static boolean isOwnedBy(String key, Long eventId) {
        return eventIdOf(key).map(eventId::equals).orElse(false);
    }

    public static Optional<Long> eventIdOf(String key) {
        Matcher m = key == null ? null : OWNED.matcher(key);
        return m != null && m.matches() ? Optional.of(Long.valueOf(m.group(1))) : Optional.empty();
    }

    static String extensionOf(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }
}
