package com.stagelog.setlist.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.stagelog.catalog.application.CatalogCommand;
import com.stagelog.catalog.application.UpsertSong;
import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.EventQuery;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.Provider;
import com.stagelog.ops.application.port.AlertPort;
import com.stagelog.setlist.application.port.PlaylistPort;
import com.stagelog.setlist.application.port.PlaylistPort.ItemSync;
import com.stagelog.setlist.application.port.SetlistFmSetlistPort;
import com.stagelog.support.IntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * setlist 통합 테스트 기반. setlist.fm·유튜브·운영자 알림 포트는 목, 도메인 이벤트는 ApplicationEvents 로 센다.
 * 매 테스트 전에 setlist·event·catalog·users 테이블을 비우고 동의한 사용자 1·2 와 관리자 9 를 넣는다.
 */
@RecordApplicationEvents
public abstract class SetlistIntegrationTest extends IntegrationTest {

    public static final long USER = 1L;
    public static final long OTHER = 2L;
    public static final long ADMIN = 9L;
    static final List<String> TABLES = List.of("setlist_unmatched_names", "setlist_revisions", "setlist_items", "setlist_pages",
            "youtube_credentials", "event_sources", "events", "song_aliases", "song_translations", "songs", "users");

    @MockitoBean
    protected AlertPort alertPort;
    @MockitoBean
    protected SetlistFmSetlistPort setlistFmPort;
    @MockitoBean
    protected PlaylistPort playlistPort;

    @Autowired
    protected ApplicationEvents applicationEvents;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected EventRepository eventRepository;
    @Autowired
    protected EventSourceRepository eventSourceRepository;
    @Autowired
    protected CatalogCommand catalogCommand;
    @Autowired
    WebApplicationContext context;

    protected MockMvc mvc;
    protected LocalDate today;

    @BeforeEach
    void setUpBase() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        today = LocalDate.now(EventQuery.LIST_ZONE);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        insertConsentedUser(USER, false);
        insertConsentedUser(OTHER, false);
        insertConsentedUser(ADMIN, true);
        // 목 기본값: 항목 맞추기는 바꾼 것 없이 OK (null 이면 동기화가 NPE 로 실패한다)
        when(playlistPort.syncItems(any(), any(), any())).thenReturn(ItemSync.ok(List.of(), 0));
    }

    // 동의 게이트(ConsentInterceptor)를 통과하려면 동의한 사용자 행이 있어야 한다
    protected void insertConsentedUser(long id, boolean admin) {
        jdbc.update("INSERT INTO users (id, nickname, locale, is_admin, consented_at, terms_version, created_at, updated_at) "
                + "VALUES (?, ?, 'ko', ?, UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", id, "user" + id, admin);
    }

    protected static RequestPostProcessor user(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(userId, false), null, List.of()));
    }

    protected static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ADMIN, true), null,
                List.of(new SimpleGrantedAuthority("ADMIN"))));
    }

    protected Long newEvent(String name, LocalDate date) {
        return eventRepository.save(Event.create(name, SupportedLocale.KO, EventType.CONCERT, date)).getId();
    }

    protected Long newEventLinkedToSetlistFm(String name, LocalDate date, String setlistFmId) {
        Event event = eventRepository.save(Event.create(name, SupportedLocale.KO, EventType.CONCERT, date));
        eventSourceRepository.save(new EventSource(event, Provider.SETLISTFM, setlistFmId, date.toString(), "{}", Instant.now()));
        return event.getId();
    }

    protected Long newSong(String koTitle, String alias, String youtubeVideoId) {
        return catalogCommand.upsertSong(new UpsertSong(null, new LocalizedText(koTitle, Map.of()), "ko",
                alias == null ? List.of() : List.of(alias), null, youtubeVideoId, null)).id();
    }

    protected static String editBody(Long... songIds) {
        StringBuilder sb = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < songIds.length; i++) {
            sb.append(i > 0 ? "," : "").append("{\"songId\":").append(songIds[i]).append(",\"order\":").append(i + 1).append("}");
        }
        return sb.append("]}").toString();
    }

    protected static DocumentContext json(MvcResult result) throws Exception {
        return JsonPath.parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected List<Long> itemSongIds(Long eventId) {
        return jdbc.queryForList("SELECT song_id FROM setlist_items WHERE event_id = ? ORDER BY position", Long.class, eventId);
    }

    protected int revisionCount(Long eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM setlist_revisions WHERE event_id = ?", Integer.class, eventId);
    }
}
