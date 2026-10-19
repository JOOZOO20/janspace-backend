package com.stagelog.event.infrastructure;

import com.stagelog.event.application.port.KopisPort;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourcePolicy.SourceVenue;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.integration.kopis.KopisClient;
import com.stagelog.integration.kopis.KopisFacility;
import com.stagelog.integration.kopis.KopisPerformance;
import com.stagelog.integration.kopis.KopisPerformanceDetail;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * KOPIS 응답 -> SourceSnapshot. 공연장은 mt13id 를 외부 ID 로 쓰고 좌표는 상위 시설(mt10id) 의 la/lo 에서 가져온다.
 * dtguidance 의 그 요일 시각이 하나면 startsAtLocal, 여럿이면 시각마다 스냅샷(키 'YYYY-MM-DD#HH:mm'), 없으면 날짜 키 하나.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KopisSourceAdapter implements KopisPort {

    static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*시간");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*분");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final KopisClient client;

    @Override
    public boolean isEnabled() {
        return client.isEnabled();
    }

    @Override
    public List<Listing> search(LocalDate from, LocalDate to, String name, LocalDate afterDate) {
        List<Listing> out = new ArrayList<>();
        for (KopisPerformance p : client.list(from, to, name, afterDate)) {
            if (p.mt20id() != null) {
                out.add(new Listing(p.mt20id(), p.prfnm(), date(p.prfpdfrom()), date(p.prfpdto())));
            }
        }
        return out;
    }

    @Override
    public List<SourceSnapshot> snapshots(String mt20id, LocalDate date) {
        Optional<KopisPerformanceDetail> found = client.detail(mt20id);
        if (found.isEmpty()) {
            return List.of();
        }
        KopisPerformanceDetail d = found.get();
        SourceVenue venue = venue(d);
        String description = description(d);
        String ticketUrl = d.relateList().stream().map(KopisPerformanceDetail.KopisRelate::relateurl)
                .filter(u -> u != null && !u.isBlank()).findFirst().orElse(null);
        Integer running = runningMinutes(d.prfruntime());
        String poster = blankToNull(d.poster());
        List<LocalTime> sessions = DtGuidanceParser.sessionsOn(d.dtguidance(), date);

        if (sessions.size() <= 1) {
            LocalTime time = sessions.isEmpty() ? null : sessions.get(0);
            return List.of(new SourceSnapshot(mt20id, date.toString(), blankToNull(d.prfnm()), description, ticketUrl, poster,
                    venue, time == null ? null : date.atTime(time), date, running));
        }
        List<SourceSnapshot> out = new ArrayList<>();
        for (LocalTime time : sessions) {
            out.add(new SourceSnapshot(mt20id, date + "#" + HH_MM.format(time), blankToNull(d.prfnm()), description, ticketUrl,
                    poster, venue, date.atTime(time), date, running));
        }
        return out;
    }

    @Override
    public Optional<Poster> downloadPoster(String url) {
        return client.downloadImage(url).map(i -> new Poster(i.bytes(), i.contentType()));
    }

    // 공연장(mt13id) 이름은 fcltynm, 좌표·주소는 시설(mt10id) 상세에서. 시설 조회 실패는 좌표 없음으로 둔다
    private SourceVenue venue(KopisPerformanceDetail d) {
        String name = blankToNull(d.fcltynm());
        if (name == null) {
            return null;
        }
        GeoPoint location = null;
        String city = null;
        if (d.mt10id() != null && !d.mt10id().isBlank()) {
            try {
                Optional<KopisFacility> facility = client.facility(d.mt10id());
                if (facility.isPresent()) {
                    location = point(facility.get().la(), facility.get().lo());
                    city = cityOf(facility.get().adres());
                }
            } catch (RuntimeException e) {
                log.warn("[KOPIS 시설 조회 실패] mt10id={}", d.mt10id(), e);
            }
        }
        String externalId = blankToNull(d.mt13id()) != null ? d.mt13id() : d.mt10id();
        return new SourceVenue(externalId, name, city, "KR", location, null);
    }

    // 출연진·연령·가격을 한 문단 설명으로
    private static String description(KopisPerformanceDetail d) {
        List<String> lines = new ArrayList<>();
        if (blankToNull(d.prfcast()) != null) {
            lines.add("출연: " + d.prfcast().trim());
        }
        if (blankToNull(d.prfage()) != null) {
            lines.add("관람연령: " + d.prfage().trim());
        }
        if (blankToNull(d.pcseguidance()) != null) {
            lines.add("가격: " + d.pcseguidance().trim());
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    // "1 시간 30 분" -> 90. 숫자가 없으면 null
    static Integer runningMinutes(String prfruntime) {
        if (prfruntime == null) {
            return null;
        }
        int total = 0;
        boolean found = false;
        Matcher h = HOURS.matcher(prfruntime);
        if (h.find()) {
            total += Integer.parseInt(h.group(1)) * 60;
            found = true;
        }
        Matcher m = MINUTES.matcher(prfruntime);
        if (m.find()) {
            total += Integer.parseInt(m.group(1));
            found = true;
        }
        return found ? total : null;
    }

    // 주소 첫 토큰(시·도) 을 도시로. "서울특별시 송파구 …" -> "서울특별시"
    private static String cityOf(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        return address.trim().split("\\s+")[0];
    }

    private static GeoPoint point(String la, String lo) {
        try {
            return la == null || lo == null || la.isBlank() || lo.isBlank() ? null
                    : new GeoPoint(Double.parseDouble(la.trim()), Double.parseDouble(lo.trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate date(String dotted) {
        try {
            return dotted == null || dotted.isBlank() ? null : LocalDate.parse(dotted.trim(), DOTTED);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
