package com.janspace.ticket.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 닫힌 세계 대조(closed-world matching): 단일 아티스트라 모든 공연이 행사 DB 에 있으므로, 티켓 글자에서 공연명·공연장을 잘라내지 않고
 * 그 날짜의 행사 후보 각각의 이름(행사·공연장, 번역 포함)이 글자 안에 얼마나 들어 있는지로 점수를 매긴다.
 * 점수(0~100) = 이름 일치 80 + 시작 시각 일치 20. 후보끼리 순서를 정하는 값이며 확률이 아니다.
 */
public final class EventMatcher {

    static final int LABEL_WEIGHT = 80;
    static final int TIME_WEIGHT = 20;
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** 대조 대상 행사. labels = 행사 이름·공연장 이름의 원어와 번역. */
    public record Target(Long eventId, LocalDate date, LocalTime startTime, List<String> labels) {
    }

    public record Scored(Target target, int score) {
    }

    private EventMatcher() {
    }

    // 점수 내림차순, 같으면 오늘에 가까운 날짜, 그다음 행사 ID
    public static List<Scored> rank(List<String> lines, List<Target> targets, LocalDate today) {
        String text = compact(String.join(" ", lines));
        List<LocalTime> times = TicketText.times(lines);
        return targets.stream()
                .map(t -> new Scored(t, score(text, times, t)))
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparingLong(s -> Math.abs(ChronoUnit.DAYS.between(today, s.target().date())))
                        .thenComparing(s -> s.target().eventId()))
                .toList();
    }

    static int score(String compactText, List<LocalTime> times, Target target) {
        double label = target.labels().stream().mapToDouble(l -> labelScore(compactText, l)).max().orElse(0);
        boolean timeHit = target.startTime() != null && times.contains(target.startTime());
        return (int) Math.round(label * LABEL_WEIGHT + (timeHit ? TIME_WEIGHT : 0));
    }

    // 이름 전체가 (공백·기호를 뺀) 글자 안에 있으면 1. 아니면 토큰 글자 수 기준 부분 일치 비율 (OCR 이 일부만 읽은 경우)
    static double labelScore(String compactText, String label) {
        String whole = compact(label);
        if (whole.length() >= 2 && compactText.contains(whole)) {
            return 1.0;
        }
        List<String> tokens = tokens(label);
        int total = tokens.stream().mapToInt(String::length).sum();
        int hit = tokens.stream().filter(compactText::contains).mapToInt(String::length).sum();
        return total == 0 ? 0 : (double) hit / total;
    }

    // NFKC + 소문자 + 글자·숫자 외 제거: "KSPO DOME" == "kspodome", "Ｋｓｐｏ　Ｄｏｍｅ" 도 같다
    static String compact(String s) {
        return NON_WORD.matcher(TicketText.nfkc(s).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    // 짧은 토큰은 우연히 들어맞으므로 버린다: 한글·한자 등은 2자 이상, 라틴 문자는 3자 이상
    static List<String> tokens(String label) {
        return Arrays.stream(NON_WORD.split(TicketText.nfkc(label).toLowerCase(Locale.ROOT)))
                .filter(t -> t.length() >= (t.chars().allMatch(c -> c < 128) ? 3 : 2))
                .toList();
    }
}
