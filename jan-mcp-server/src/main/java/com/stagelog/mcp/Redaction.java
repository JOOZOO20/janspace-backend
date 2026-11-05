package com.stagelog.mcp;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Tool 응답·호출 로그에서 개인정보·토큰·webhook URL 을 지운다. 메인 앱 조회 API 는 이런 값을 내지 않지만,
 * 동기화 실패 요약 같은 자유 텍스트에 외부 응답(오류 메시지·요청 URL)이 섞일 수 있어 내보내기 직전에 한 번 더 거른다.
 * 치환 결과에 따옴표를 넣지 않으므로 JSON 문자열 안에서 써도 JSON 이 깨지지 않는다.
 */
public final class Redaction {

    static final String MASK = "[REDACTED]";

    // 순서가 중요하다: webhook URL 을 먼저 통째로 지우고, 그다음 URL 쿼리의 비밀 값, 토큰, 이메일
    private static final List<Pattern> WHOLE = List.of(
            Pattern.compile("(?i)https?://[^\\s\"'<>]*(?:webhook|hooks\\.slack\\.com)[^\\s\"'<>]*"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
            Pattern.compile("ya29\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("1//[A-Za-z0-9_-]{20,}"),
            Pattern.compile("AIza[0-9A-Za-z_-]{35}"),
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i)([?&](?:service_?key|api_?key|apikey|key|token|access_token|refresh_token|client_secret|secret|password|code)=)[^&\\s\"'<>]+");

    private Redaction() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern pattern : WHOLE) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        return SECRET_PARAM.matcher(result).replaceAll("$1" + MASK);
    }
}
