package com.janspace.ticket.domain;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기기 OCR 이 읽은 티켓 글자 줄에서 날짜·시각·좌석을 뽑는 순수 함수. 공연명·공연장은 잘라내지 않는다(행사 DB 와 대조한다, EventMatcher).
 * 날짜는 해석이 여럿이면 모두 낸다(일/월 순서, 연도 없는 날짜의 앞뒤 연도). 실제 행사가 있는 날짜만 후보가 되므로 행사 DB 가 걸러 준다.
 */
public final class TicketText {

    static final int MAX_DATES = 10;
    static final int SEAT_MAX = Ticket.SEAT_MAX;
    // 요일만 있는 연도 없는 날짜에서 거슬러 찾는 범위 (오래된 티켓을 소급해 찍는 경우)
    static final int WEEKDAY_LOOKBACK_YEARS = 15;

    private static final String MONTHS = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
            + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

    // 연도가 있는 형식. 앞의 것부터 찾고 찾은 자리는 지워서 뒤 패턴(연도 없는 형식 등)이 다시 물지 않게 한다
    private static final Pattern YMD_NUM = Pattern.compile("(?<!\\d)(\\d{4})\\s*[./-]\\s*(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern YMD_CJK = Pattern.compile("(?<!\\d)(\\d{4})\\s*[년年]\\s*(\\d{1,2})\\s*[월月]\\s*(\\d{1,2})\\s*[일日]?");
    private static final Pattern DMY_NUM = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})\\s*[./-]\\s*(\\d{4})(?!\\d)");
    private static final Pattern MDY_EN = Pattern.compile("(?i)(?<![a-z])" + MONTHS + "(?![a-z])\\.?\\s*(\\d{1,2})(?:st|nd|rd|th)?\\s*,?\\s*(\\d{4})(?!\\d)");
    private static final Pattern DMY_EN = Pattern.compile("(?i)(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s./-]*" + MONTHS + "(?![a-z])\\.?[\\s,./-]*(\\d{4})(?!\\d)");
    private static final Pattern YY_MD = Pattern.compile("(?<![\\d.])(\\d{2})\\s*[./-]\\s*(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})(?![\\d.])");
    // 연도 없는 형식
    private static final Pattern MD_CJK = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[월月]\\s*(\\d{1,2})\\s*[일日]");
    private static final Pattern MD_EN = Pattern.compile("(?i)(?<![a-z])" + MONTHS + "(?![a-z])\\.?\\s*(\\d{1,2})(?:st|nd|rd|th)?(?!\\d)");
    private static final Pattern DM_EN = Pattern.compile("(?i)(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?\\s*" + MONTHS + "(?![a-z])");
    // 숫자만인 월.일 은 오인식이 많아 요일이 붙었거나 두 자리.두 자리(09.16)일 때만 받는다. '-' 는 전화번호·구역 번호와 겹쳐 받지 않는다
    private static final Pattern MD_NUM = Pattern.compile("(?<![\\d.:/])(\\d{1,2})\\s*[./]\\s*(\\d{1,2})(?![\\d.:/])");
    // 날짜 바로 뒤의 요일: (수) (水) 수요일 水曜日 (Wed) Wed
    private static final Pattern WEEKDAY = Pattern.compile(
            "(?i)^\\s*(?:[(\\[]\\s*([월화수목금토일月火水木金土日])[^)\\]]{0,4}[)\\]]|([월화수목금토일])요일|([月火水木金土日])曜"
                    + "|[(\\[]?\\s*(mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"
                    + "(?![a-z])\\.?\\s*[)\\]]?)");
    private static final String KO_WEEK = "월화수목금토일";
    private static final String JA_WEEK = "月火水木金土日";
    private static final List<String> EN_WEEK = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    private static final Pattern TIME_COLON = Pattern.compile("(?i)(오전|오후|午前|午後)?\\s*(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?![a-z])");
    private static final Pattern TIME_HOUR = Pattern.compile("(오전|오후|午前|午後)?\\s*(?<!\\d)(\\d{1,2})\\s*[시時]\\s*(?:(\\d{1,2})\\s*[분分])?");
    private static final Pattern TIME_AMPM = Pattern.compile("(?i)(?<![\\d:])(\\d{1,2})\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])");

    // 좌석 조각. 한 줄에서 순서대로 모으고, 여러 줄(스텁 중복 포함)은 같은 조각을 한 번만 쓴다
    private static final Pattern SEAT_PART = Pattern.compile(String.join("|",
            "(?:정리|입장)\\s*번호\\s*:?\\s*\\d{1,5}(?:\\s*번)?",
            "整理\\s*番号\\s*:?\\s*\\d{1,5}(?:\\s*番)?",
            "(?<![\\d-])\\d{1,2}\\s*[층階](?![가-힣])",
            "[A-Za-z0-9가-힣]{1,4}\\s*구역(?![가-힣])",
            "[A-Za-z0-9가-힣]{1,4}\\s*블[록럭]",
            "[A-Za-z0-9]{1,4}\\s*ブロック",
            "(?<![\\d-])[A-Za-z0-9]{1,4}\\s*[열列](?![가-힣])",
            "(?<![\\d-])\\d{1,4}\\s*[번番](?![가-힣])",
            "(?:[A-Za-z]{1,4}|스탠딩|지정|비지정|자유)\\s*석",
            "스탠딩|アリーナ|スタンド",
            "(?i)(?<![a-z])(?:section|sec|row|seat|gate|block|zone|floor|entrance)(?![a-z])\\.?\\s*(?:no\\.?)?\\s*:?\\s*"
                    + "(?:[a-z]{0,2}\\d{1,4}[a-z]?|[a-z](?![a-z]))",
            "(?i)(?<![a-z])standing(?![a-z])"));
    // 숫자·단위 사이 공백 정리: "1 층" -> "1층", "정리 번호" -> "정리번호"
    private static final Pattern SPACE_BEFORE_CJK = Pattern.compile("(?<=[\\p{L}\\p{N}])\\s+(?=[\\p{IsHangul}\\p{IsHan}\\p{IsKatakana}])");

    // 숫자 영역의 OCR 오인식(O -> 0, l·I·| -> 1). 진짜 숫자가 하나 이상 섞인 두 글자 이상의 덩어리에서만 바꾼다 (SEOUL·Hall 은 그대로)
    private static final Pattern DIGITISH = Pattern.compile("[0-9OoIl|]{2,}");

    private TicketText() {
    }

    public static List<LocalDate> dates(List<String> lines, LocalDate today) {
        StringBuilder text = new StringBuilder(String.join("\n", lines.stream().map(TicketText::forNumbers).toList()));
        Set<LocalDate> out = new LinkedHashSet<>();

        // 1. 연도가 있는 날짜. 찾은 연도는 같은 티켓의 연도 없는 날짜(2026.09.16 ~ 09.18)에 쓴다
        Set<Integer> years = new LinkedHashSet<>();
        find(text, YMD_NUM, m -> withWeekday(text, m, Arrays.asList(ymd(m.group(1), m.group(2), m.group(3)))), out, years);
        find(text, YMD_CJK, m -> withWeekday(text, m, Arrays.asList(ymd(m.group(1), m.group(2), m.group(3)))), out, years);
        find(text, DMY_NUM, m -> withWeekday(text, m, Arrays.asList(ymd(m.group(3), m.group(2), m.group(1)), ymd(m.group(3), m.group(1), m.group(2)))),
                out, years);
        find(text, MDY_EN, m -> withWeekday(text, m, Arrays.asList(ymd(m.group(3), month(m.group(1)), m.group(2)))), out, years);
        find(text, DMY_EN, m -> withWeekday(text, m, Arrays.asList(ymd(m.group(3), month(m.group(2)), m.group(1)))), out, years);
        find(text, YY_MD, m -> withWeekday(text, m, Arrays.asList(ymd("20" + m.group(1), m.group(2), m.group(3)))), out, years);

        // 2. 연도 없는 날짜
        find(text, MD_CJK, m -> yearless(text, m, num(m.group(1)), num(m.group(2)), years, today), out, null);
        find(text, MD_EN, m -> yearless(text, m, month(m.group(1)), num(m.group(2)), years, today), out, null);
        find(text, DM_EN, m -> yearless(text, m, month(m.group(2)), num(m.group(1)), years, today), out, null);
        find(text, MD_NUM, m -> {
            boolean twoByTwo = m.group(1).length() == 2 && m.group(2).length() == 2;
            if (!twoByTwo && weekday(text, m.end()) == null) {
                return List.of();
            }
            return yearless(text, m, num(m.group(1)), num(m.group(2)), years, today);
        }, out, null);

        return out.stream().limit(MAX_DATES).toList();
    }

    // 시각 후보. 오전·오후 표시가 없는 1~11시는 낮·저녁 둘 다 낸다 (7시 -> 07:00, 19:00)
    public static List<LocalTime> times(List<String> lines) {
        String text = String.join("\n", lines.stream().map(TicketText::forNumbers).toList());
        Set<LocalTime> out = new LinkedHashSet<>();
        Matcher m = TIME_COLON.matcher(text);
        while (m.find()) {
            addTime(out, num(m.group(2)), num(m.group(3)), marker(m.group(1), m.group(4)));
        }
        m = TIME_HOUR.matcher(text);
        while (m.find()) {
            addTime(out, num(m.group(2)), m.group(3) == null ? 0 : num(m.group(3)), marker(m.group(1), null));
        }
        m = TIME_AMPM.matcher(text);
        while (m.find()) {
            addTime(out, num(m.group(1)), 0, marker(null, m.group(2)));
        }
        return List.copyOf(out);
    }

    // 좌석 문자열. 없으면 null
    public static String seat(List<String> lines) {
        Set<String> parts = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher m = SEAT_PART.matcher(forNumbers(line));
            while (m.find()) {
                parts.add(SPACE_BEFORE_CJK.matcher(m.group().trim().replaceAll("\\s+", " ")).replaceAll(""));
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        String seat = String.join(" ", parts);
        return seat.length() <= SEAT_MAX ? seat : seat.substring(0, SEAT_MAX).trim();
    }

    // 전각 숫자·기호를 반각으로(NFKC), 숫자 덩어리 안의 O·l·I·| 를 숫자로
    static String forNumbers(String line) {
        Matcher m = DIGITISH.matcher(nfkc(line));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String run = m.group();
            String fixed = run.chars().anyMatch(Character::isDigit)
                    ? run.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('|', '1')
                    : run;
            m.appendReplacement(out, Matcher.quoteReplacement(fixed));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String nfkc(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC);
    }

    private interface Hypotheses {
        List<LocalDate> of(Matcher m);
    }

    // 패턴을 모두 찾아 날짜 가설을 모으고, 찾은 자리를 공백으로 지운다(길이 유지)
    private static void find(StringBuilder text, Pattern pattern, Hypotheses hypotheses, Set<LocalDate> out, Set<Integer> years) {
        Matcher m = pattern.matcher(text);
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            List<LocalDate> found = hypotheses.of(m).stream().filter(d -> d != null).toList();
            if (found.isEmpty()) {
                continue;
            }
            out.addAll(found);
            if (years != null) {
                found.forEach(d -> years.add(d.getYear()));
            }
            spans.add(new int[]{m.start(), m.end()});
        }
        spans.forEach(s -> text.replace(s[0], s[1], " ".repeat(s[1] - s[0])));
    }

    // 요일이 붙어 있고 그 요일과 맞는 가설이 있으면 그것만 남긴다 (OCR 이 요일을 틀리게 읽었으면 전부 둔다)
    private static List<LocalDate> withWeekday(CharSequence text, Matcher m, List<LocalDate> hypotheses) {
        DayOfWeek dow = weekday(text, m.end());
        List<LocalDate> valid = hypotheses.stream().filter(d -> d != null).distinct().toList();
        if (dow == null) {
            return valid;
        }
        List<LocalDate> matching = valid.stream().filter(d -> d.getDayOfWeek() == dow).toList();
        return matching.isEmpty() ? valid : matching;
    }

    // 티켓에 적힌 다른 날짜의 연도 + (요일이 있으면) 요일이 맞는 가장 가까운 해 / (없으면) 오늘 기준 가장 가까운 지난 날·다가올 날
    private static List<LocalDate> yearless(CharSequence text, Matcher m, int month, int day, Set<Integer> years, LocalDate today) {
        Set<LocalDate> out = new LinkedHashSet<>();
        years.forEach(y -> addIfValid(out, y, month, day));
        DayOfWeek dow = weekday(text, m.end());
        LocalDate best = null;
        if (dow != null) {
            for (int y = today.getYear() + 1; y >= today.getYear() - WEEKDAY_LOOKBACK_YEARS; y--) {
                LocalDate d = date(y, month, day);
                if (d != null && d.getDayOfWeek() == dow && (best == null || distance(today, d) < distance(today, best))) {
                    best = d;
                }
            }
        }
        if (best != null) {
            out.add(best);
            return List.copyOf(out);
        }
        List<LocalDate> around = new ArrayList<>();
        for (int y = today.getYear() - 1; y <= today.getYear() + 1; y++) {
            addIfValid(around, y, month, day);
        }
        around.stream().filter(d -> !d.isAfter(today)).max(Comparator.naturalOrder()).ifPresent(out::add);
        around.stream().filter(d -> d.isAfter(today)).min(Comparator.naturalOrder()).ifPresent(out::add);
        return out.stream().sorted(Comparator.comparingLong(d -> distance(today, d))).toList();
    }

    private static void addIfValid(java.util.Collection<LocalDate> out, int year, int month, int day) {
        LocalDate d = date(year, month, day);
        if (d != null) {
            out.add(d);
        }
    }

    static DayOfWeek weekday(CharSequence text, int from) {
        Matcher m = WEEKDAY.matcher(text.subSequence(from, Math.min(text.length(), from + 16)));
        if (!m.find()) {
            return null;
        }
        if (m.group(1) != null) {
            int ko = KO_WEEK.indexOf(m.group(1));
            return DayOfWeek.of((ko >= 0 ? ko : JA_WEEK.indexOf(m.group(1))) + 1);
        }
        if (m.group(2) != null) {
            return DayOfWeek.of(KO_WEEK.indexOf(m.group(2)) + 1);
        }
        if (m.group(3) != null) {
            return DayOfWeek.of(JA_WEEK.indexOf(m.group(3)) + 1);
        }
        return DayOfWeek.of(EN_WEEK.indexOf(m.group(4).substring(0, 3).toLowerCase(Locale.ROOT)) + 1);
    }

    private static final Map<String, Integer> MONTH_INDEX = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4), Map.entry("may", 5),
            Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9), Map.entry("oct", 10),
            Map.entry("nov", 11), Map.entry("dec", 12));

    private static int month(String name) {
        return MONTH_INDEX.get(name.substring(0, 3).toLowerCase(Locale.ROOT));
    }

    private static LocalDate ymd(String year, String month, String day) {
        return date(num(year), num(month), num(day));
    }

    private static LocalDate ymd(String year, int month, String day) {
        return date(num(year), month, num(day));
    }

    // 없는 날짜(13월, 2월 30일)는 null
    private static LocalDate date(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static long distance(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    private static int num(String digits) {
        return Integer.parseInt(digits);
    }

    // +1 = 오후, -1 = 오전, 0 = 표시 없음
    private static int marker(String prefix, String suffix) {
        String p = prefix == null ? "" : prefix;
        String s = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
        if (p.equals("오후") || p.equals("午後") || s.startsWith("p")) {
            return 1;
        }
        if (p.equals("오전") || p.equals("午前") || s.startsWith("a")) {
            return -1;
        }
        return 0;
    }

    private static void addTime(Set<LocalTime> out, int hour, int minute, int marker) {
        if (hour > 23 || minute > 59) {
            return;
        }
        if (marker > 0 && hour < 12) {
            out.add(LocalTime.of(hour + 12, minute));
        } else if (marker < 0 && hour == 12) {
            out.add(LocalTime.of(0, minute));
        } else {
            out.add(LocalTime.of(hour, minute));
            if (marker == 0 && hour >= 1 && hour <= 11) {
                out.add(LocalTime.of(hour + 12, minute));
            }
        }
    }
}
