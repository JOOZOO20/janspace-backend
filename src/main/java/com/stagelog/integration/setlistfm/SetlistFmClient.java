package com.stagelog.integration.setlistfm;

import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * setlist.fm REST 1.0. 헤더 x-api-key, Accept: application/json. 범위를 넘은 페이지는 404 를 주므로 빈 페이지로 바꾼다.
 * 호출 사이에 최소 간격을 둔다: API 키 한도가 초당 2회인데 행사 동기화(최대 20페이지 연속)와 셋리 병합(행사마다 단건)이
 * 같은 키를 연달아 부른다.
 */
@Slf4j
@Component
public class SetlistFmClient {

    static final Duration MIN_INTERVAL = Duration.ofMillis(500);

    private final SetlistFmProperties props;
    private final RestClient restClient;
    private long lastCallNanos;

    public SetlistFmClient(SetlistFmProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public boolean isEnabled() {
        return props.apiKey() != null && !props.apiKey().isBlank() && props.artistMbid() != null && !props.artistMbid().isBlank();
    }

    public String artistMbid() {
        return props.artistMbid();
    }

    public SetlistFmSetlist getSetlist(String id) throws RestClientException {
        throttle();
        return restClient.get().uri(props.baseUri() + "/setlist/{id}", id)
                .header("x-api-key", props.apiKey())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(SetlistFmSetlist.class);
    }

    public SetlistFmSetlistsPage listArtistSetlists(String mbid, int page) throws RestClientException {
        throttle();
        try {
            SetlistFmSetlistsPage result = restClient.get().uri(props.baseUri() + "/artist/{mbid}/setlists?p={page}", mbid, page)
                    .header("x-api-key", props.apiKey())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve().body(SetlistFmSetlistsPage.class);
            log.info("[setlist.fm 아티스트 셋리 조회] mbid={}, page={}, 결과={}", mbid, page, result == null ? 0 : result.items().size());
            return result == null ? new SetlistFmSetlistsPage(0, page, 0, List.of()) : result;
        } catch (HttpClientErrorException.NotFound e) {
            // 1페이지부터 404 면 범위 초과가 아니라 MBID 설정 오류일 가능성이 크다
            if (page == 1) {
                log.warn("[setlist.fm 아티스트 없음] 1페이지가 404. SETLISTFM_ARTIST_MBID 확인 필요. mbid={}", mbid);
            }
            return new SetlistFmSetlistsPage(0, page, 0, List.of());
        }
    }

    // ponytail: 인스턴스 하나 기준 간격. 여러 인스턴스가 같은 키를 쓰면 공유 레이트 리미터(Redis)로 바꾼다
    private synchronized void throttle() {
        long waitNanos = lastCallNanos + MIN_INTERVAL.toNanos() - System.nanoTime();
        if (lastCallNanos != 0 && waitNanos > 0) {
            try {
                Thread.sleep(Duration.ofNanos(waitNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallNanos = System.nanoTime();
    }
}
