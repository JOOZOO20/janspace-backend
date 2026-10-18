package com.janspace.catalog.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * setlist.fm 표기 정규화 정책. 별칭을 저장할 때와 병합에서 대조할 때 같은 함수를 써야 "for lovers who hesitate" 같은
 * 제멋대로인 표기가 맞는다. 규칙: 앞뒤 공백·따옴표·문장부호 제거(괄호는 유지), 소문자, 연속 공백 하나로,
 * 악센트 제거(NFD 후 결합 문자 삭제) 뒤 NFC. song_aliases 의 DB 콜레이션(utf8mb4_0900_ai_ci)이 대소문자·악센트를 무시하므로
 * Java 쪽 비교(제목 매칭·별칭 중복 제거)도 같은 기준이어야 DB 유일 제약과 어긋나지 않는다. 한계: 전각·반각 차이는 맞추지 않는다.
 */
public final class AliasNormalizer {

    // 괄호는 남긴다: "She (feat. X)" 의 닫는 괄호를 지우면 짝이 깨진다
    private static final String EDGE = "[\\s\"'\u201C\u201D\u2018\u2019.,!?;:\\-\u2013\u2014]+";
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^" + EDGE + "|" + EDGE + "$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private AliasNormalizer() {
    }

    // null·공백만 있는 입력은 빈 문자열. 호출자가 isBlank 로 걸러 저장·대조하지 않는다
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String unaccented = COMBINING_MARKS.matcher(Normalizer.normalize(raw, Normalizer.Form.NFD)).replaceAll("");
        String stripped = EDGE_PUNCTUATION.matcher(Normalizer.normalize(unaccented, Normalizer.Form.NFC)).replaceAll("");
        return WHITESPACE.matcher(stripped).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
