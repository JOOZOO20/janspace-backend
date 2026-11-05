package com.stagelog.setlist.support;

import com.stagelog.catalog.application.CatalogCommand;
import com.stagelog.catalog.application.UpsertSong;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.EventQuery;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.integration.youtube.YouTubeClient;
import com.stagelog.ops.application.port.AlertPort;
import com.stagelog.setlist.application.YouTubeQuota;
import com.stagelog.setlist.domain.YouTubeCredential;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import com.stagelog.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * Fake YouTube 서버를 실제 YouTubeClient·어댑터·동기화 유스케이스에 붙인 통합 테스트 기반. 운영자 알림 포트만 목이다.
 * 다른 통합 테스트는 ProviderMockConfig 의 MockRestServiceServer 를 쓰므로, 여기서는 별도 RestClient 로 만든 YouTubeClient 를 @Primary 로 둔다.
 * Quota 예산은 크게 잡고(수정 전 코드에는 없는 설정이라 무시된다), 한도를 보는 테스트는 api_quota_daily 행을 직접 바꾼다.
 */
@Import(FakeYouTubeIntegrationTest.FakeYouTubeConfig.class)
@TestPropertySource(properties = {"jan.youtube.quota.auto-units=1000000", "jan.youtube.quota.manual-units=1000000"})
public abstract class FakeYouTubeIntegrationTest extends IntegrationTest {

    protected static final FakeYouTubeServer FAKE = FakeYouTubeServer.start();

    @MockitoBean
    protected AlertPort alertPort;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected EventRepository eventRepository;
    @Autowired
    protected CatalogCommand catalogCommand;
    @Autowired
    protected YouTubeCredentialRepository credentials;
    @Autowired
    protected YouTubeQuota quota;

    @TestConfiguration
    static class FakeYouTubeConfig {
        @Bean
        @Primary
        YouTubeClient fakeYouTubeClient() {
            return new YouTubeClient(RestClient.builder().requestInterceptor(FAKE.redirector()), "cid", "csec");
        }
    }

    protected void connectChannel() {
        if (credentials.findById(YouTubeCredential.SINGLETON_ID).isEmpty()) {
            credentials.save(new YouTubeCredential("rt"));
        }
        jdbc.update("UPDATE youtube_credentials SET invalid_at = NULL");
    }

    // 원장·일일 합계·미룸 표시를 비우고 오늘 일일 행의 한도를 정한다 (다른 테스트 컨텍스트가 기본 예산으로 먼저 만들었을 수 있다)
    protected void resetQuota(int autoLimit, int manualLimit) {
        quota.prepareDays();
        jdbc.update("DELETE FROM api_quota_ledger");
        jdbc.update("UPDATE api_quota_daily SET used_units = 0, reserved_units = 0, exhausted_at = NULL, "
                + "limit_units = CASE bucket WHEN 'AUTO' THEN ? ELSE ? END", autoLimit, manualLimit);
        jdbc.update("UPDATE setlist_pages SET youtube_sync_deferred_at = NULL");
    }

    protected int quotaColumn(String column, String bucket) {
        return jdbc.queryForObject("SELECT " + column + " FROM api_quota_daily WHERE provider = 'YOUTUBE' AND quota_date = ? AND bucket = ?",
                Integer.class, quota.today(), bucket);
    }

    protected Long newEvent(String name, LocalDate date) {
        return eventRepository.save(Event.create(name, SupportedLocale.KO, EventType.CONCERT, date)).getId();
    }

    protected LocalDate today() {
        return LocalDate.now(EventQuery.LIST_ZONE);
    }

    protected Long newSong(String koTitle, String youtubeVideoId) {
        return catalogCommand.upsertSong(new UpsertSong(null, new LocalizedText(koTitle, Map.of()), "ko",
                List.of(), null, youtubeVideoId, null)).id();
    }

    // 셋리 페이지(재생목록 ID 포함)와 항목을 바로 쓴다. 편집 API 를 거치지 않으므로 SetlistChanged 자동 동기화가 돌지 않는다
    protected void writeSetlist(Long eventId, String playlistId, List<Long> songIds) {
        jdbc.update("INSERT INTO setlist_pages (event_id, order_confirmed, version, youtube_playlist_id, created_at, updated_at) "
                + "VALUES (?, FALSE, 1, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)) "
                + "ON DUPLICATE KEY UPDATE youtube_playlist_id = VALUES(youtube_playlist_id), version = version + 1", eventId, playlistId);
        jdbc.update("DELETE FROM setlist_items WHERE event_id = ?", eventId);
        for (int i = 0; i < songIds.size(); i++) {
            jdbc.update("INSERT INTO setlist_items (event_id, song_id, position, in_setlistfm, created_at, updated_at) "
                    + "VALUES (?, ?, ?, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", eventId, songIds.get(i), i + 1);
        }
    }
}
