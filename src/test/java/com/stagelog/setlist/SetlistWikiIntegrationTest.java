package com.stagelog.setlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.account.application.event.UserWithdrawn;
import com.stagelog.setlist.domain.SetlistPage;
import com.stagelog.setlist.domain.SetlistPageRepository;
import com.stagelog.setlist.support.SetlistIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E1 편집, E2 하루 한도(UTC), E3 되돌리기, E6 탈퇴, 순서 확정 독립, 인증 규칙, playlistUrl.
 */
class SetlistWikiIntegrationTest extends SetlistIntegrationTest {

    @Autowired
    SetlistPageRepository pages;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    TransactionTemplate tx;

    Long eventId;
    Long a;
    Long b;
    Long c;

    @BeforeEach
    void setUp() {
        eventId = newEvent("잔나비 단독 공연", today.plusDays(1));
        a = newSong("주저하는 연인들을 위해", "for lovers who hesitate", "vidA");
        b = newSong("악몽", "bad dreams", "vidB");
        c = newSong("전설", "LEGEND", "vidC");
    }

    @Test
    @DisplayName("E1: PUT 은 항목과 editor_user_id 가 있는 FAN 리비전을 남기고, 응답·역사 JSON 어디에도 editor 가 없다")
    void edit_writes_items_and_anonymous_revision() throws Exception {
        String body = mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER))
                        .contentType(MediaType.APPLICATION_JSON).content(editBody(a, b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value(eventId))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.orderConfirmed").value(false))
                .andExpect(jsonPath("$.data.entries[0].songId").value(a))
                .andExpect(jsonPath("$.data.entries[0].order").value(1))
                .andExpect(jsonPath("$.data.entries[0].notOnSetlistFm").value(true))
                .andExpect(jsonPath("$.data.entries[1].songId").value(b))
                .andExpect(jsonPath("$.data.updatedAt").value(org.hamcrest.Matchers.endsWith("Z")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContainIgnoringCase("editor");

        assertThat(itemSongIds(eventId)).containsExactly(a, b);
        Map<String, Object> revision = jdbc.queryForMap("SELECT editor_user_id, source, version FROM setlist_revisions WHERE event_id = ?", eventId);
        assertThat(((Number) revision.get("editor_user_id")).longValue()).isEqualTo(USER);
        assertThat(revision.get("source")).isEqualTo("FAN");
        assertThat(((Number) revision.get("version")).intValue()).isEqualTo(1);

        String history = mvc.perform(get("/api/v1/events/{id}/setlist/history", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].source").value("FAN"))
                .andExpect(jsonPath("$.data[0].entries.length()").value(2))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(history).doesNotContainIgnoringCase("editor");

        // 중복 곡은 하나로, 순서는 1..n 으로 다시 매긴다
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"songId\":" + b + ",\"order\":5},{\"songId\":" + a + ",\"order\":2},{\"songId\":" + b + ",\"order\":9}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.entries.length()").value(2))
                .andExpect(jsonPath("$.data.entries[0].songId").value(a))
                .andExpect(jsonPath("$.data.entries[1].order").value(2));
    }

    @Test
    @DisplayName("편집 오류: 없는 곡 404 SONG_NOT_FOUND, 없는 행사 404 EVENT_NOT_FOUND, 버전 불일치 409 SETLIST_VERSION_CONFLICT, 빈 페이지 GET 은 version 0")
    void edit_errors_and_empty_page() throws Exception {
        mvc.perform(get("/api/v1/events/{id}/setlist", eventId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0)).andExpect(jsonPath("$.data.entries.length()").value(0))
                .andExpect(jsonPath("$.data.updatedAt").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/events/{id}/setlist", 999999)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a, 424242L)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
        mvc.perform(put("/api/v1/events/{id}/setlist", 999999).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"songId\":" + a + ",\"order\":1}],\"version\":7}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETLIST_VERSION_CONFLICT"));
        // 맞는 버전(0)은 통과
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"songId\":" + a + ",\"order\":1}],\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("E2: 20회 편집 뒤 21회째는 429 SETLIST_EDIT_LIMIT, 다른 사용자는 허용, 리비전을 하루 전으로 옮기면(UTC 경계) 다시 허용")
    void daily_quota_utc() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON)
                            .content(i % 2 == 0 ? editBody(a, b) : editBody(b, a)))
                    .andExpect(status().isOk());
        }
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("SETLIST_EDIT_LIMIT"));
        // 되돌리기도 같은 한도
        mvc.perform(post("/api/v1/events/{id}/setlist/revert", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("SETLIST_EDIT_LIMIT"));
        assertThat(revisionCount(eventId)).isEqualTo(20);
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(OTHER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a)))
                .andExpect(status().isOk());

        // 어제(UTC) 편집으로 만들면 오늘 한도가 비워진다
        jdbc.update("UPDATE setlist_revisions SET created_at = created_at - INTERVAL 1 DAY WHERE editor_user_id = ?", USER);
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a, b, c)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.entries.length()").value(3));
    }

    @Test
    @DisplayName("E3: v1 로 되돌리면 항목이 v1 스냅샷이 되고 REVERT 리비전이 남으며 역사는 최신순. 없는 버전은 404 REVISION_NOT_FOUND")
    void revert() throws Exception {
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a, b))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(c))).andExpect(status().isOk());

        mvc.perform(post("/api/v1/events/{id}/setlist/revert", eventId).with(user(OTHER)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.entries[0].songId").value(a))
                .andExpect(jsonPath("$.data.entries[1].songId").value(b));
        assertThat(itemSongIds(eventId)).containsExactly(a, b);

        mvc.perform(get("/api/v1/events/{id}/setlist/history", eventId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(3)).andExpect(jsonPath("$.data[0].source").value("REVERT"))
                .andExpect(jsonPath("$.data[1].version").value(2)).andExpect(jsonPath("$.data[2].version").value(1));
        assertThat(((Number) jdbc.queryForObject("SELECT editor_user_id FROM setlist_revisions WHERE event_id = ? AND version = 3", Long.class, eventId)).longValue())
                .isEqualTo(OTHER);

        // 현재 버전으로 되돌리기는 아무것도 바꾸지 않는다 (리비전·한도 소모 없음)
        mvc.perform(post("/api/v1/events/{id}/setlist/revert", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3));
        assertThat(revisionCount(eventId)).isEqualTo(3);

        mvc.perform(post("/api/v1/events/{id}/setlist/revert", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":42}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
    }

    @Test
    @DisplayName("E6: UserWithdrawn 을 받으면 그 사용자의 editor_user_id 만 NULL 이 된다")
    void user_withdrawn_clears_editor() throws Exception {
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(OTHER)).contentType(MediaType.APPLICATION_JSON).content(editBody(b))).andExpect(status().isOk());

        tx.executeWithoutResult(s -> publisher.publishEvent(new UserWithdrawn(USER)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setlist_revisions WHERE editor_user_id IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setlist_revisions WHERE editor_user_id = ?", Integer.class, OTHER)).isEqualTo(1);
        assertThat(revisionCount(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("관리자 순서 확정 토글은 merged_at 과 독립이다")
    void order_confirmed_independent_of_merged_at() throws Exception {
        mvc.perform(put("/api/v1/admin/events/{id}/setlist/order-confirmed", eventId).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderConfirmed").value(true));
        assertThat(jdbc.queryForObject("SELECT merged_at FROM setlist_pages WHERE event_id = ?", Object.class, eventId)).isNull();
        mvc.perform(get("/api/v1/events/{id}/setlist", eventId)).andExpect(jsonPath("$.data.orderConfirmed").value(true));

        mvc.perform(put("/api/v1/admin/events/{id}/setlist/order-confirmed", eventId).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderConfirmed").value(false));
    }

    @Test
    @DisplayName("인증 규칙: 익명 PUT/revert 401, 일반 사용자의 관리자 API 403, 관리자 콜백은 인증 없이 열리되 state 없으면 400")
    void auth_rules() throws Exception {
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).contentType(MediaType.APPLICATION_JSON).content(editBody(a)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/v1/events/{id}/setlist/revert", eventId).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isUnauthorized());
        for (var req : List.of(get("/api/v1/admin/songs/unmatched"),
                post("/api/v1/admin/events/{id}/setlist/merge", eventId),
                post("/api/v1/admin/events/{id}/setlist/playlist", eventId),
                get("/api/v1/admin/youtube/authorize"),
                put("/api/v1/admin/events/{id}/setlist/order-confirmed", eventId).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))) {
            mvc.perform(req.with(user(USER))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get("/api/v1/admin/youtube/callback").param("code", "x").param("state", "bogus"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("재생목록이 있으면 GET /events/{id} 와 목록의 playlistUrl 에 나온다")
    void playlist_url_in_event_response() throws Exception {
        mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.playlistUrl").value(org.hamcrest.Matchers.nullValue()));
        SetlistPage page = pages.save(new SetlistPage(eventId));
        page.changePlaylist("PLabc123");
        pages.save(page);

        mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.playlistUrl").value("https://www.youtube.com/playlist?list=PLabc123"));
        mvc.perform(get("/api/v1/events").param("tab", "UPCOMING")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id == " + eventId + ")].playlistUrl").value("https://www.youtube.com/playlist?list=PLabc123"));
    }
}
