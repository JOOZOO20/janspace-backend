package com.janspace.setlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.setlist.application.MergeSetlistFmUseCase;
import com.janspace.setlist.application.SeedSetlistUseCase;
import com.janspace.setlist.application.event.SetlistMerged;
import com.janspace.setlist.application.port.FmSetlist;
import com.janspace.setlist.support.SetlistIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E4 병합(미매칭·리비전·merged_at·SetlistMerged·알림 1회), E5 재병합 무변경, E8 시드, E9 미매칭 연결 후 재병합, 스케줄 대상 선택.
 */
class SetlistMergeIntegrationTest extends SetlistIntegrationTest {

    @Autowired
    MergeSetlistFmUseCase mergeUseCase;
    @Autowired
    SeedSetlistUseCase seedUseCase;
    @Autowired
    TransactionTemplate tx;

    Long eventId;
    Long a;
    Long b;
    Long d;

    @BeforeEach
    void setUp() throws Exception {
        eventId = newEventLinkedToSetlistFm("잔나비 단독 공연", today.minusDays(1), "fm1");
        a = newSong("주저하는 연인들을 위해", "for lovers who hesitate", "vidA");
        b = newSong("악몽", "bad dreams", "vidB");
        d = newSong("전설", "LEGEND", "vidD");
        // 위키: [A, B]
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a, b)))
                .andExpect(status().isOk());
        when(setlistFmPort.fetch("fm1")).thenReturn(Optional.of(
                new FmSetlist(List.of("bad dreams", "LEGEND", "Unknown Song"))));
    }

    @Test
    @DisplayName("E4·E5: 병합은 미매칭 행·SETLISTFM_MERGE 리비전·merged_at·SetlistMerged 1회·알림 1회를 남기고, 같은 입력의 재병합은 아무것도 하지 않는다")
    void merge_then_merge_again_unchanged() throws Exception {
        assertThat(mergeUseCase.merge(eventId)).isTrue();

        assertThat(itemSongIds(eventId)).containsExactly(b, d, a);
        assertThat(jdbc.queryForList("SELECT in_setlistfm FROM setlist_items WHERE event_id = ? ORDER BY position", Boolean.class, eventId))
                .containsExactly(true, true, false);
        assertThat(jdbc.queryForList("SELECT raw_name FROM setlist_unmatched_names WHERE event_id = ?", String.class, eventId))
                .containsExactly("Unknown Song");
        Map<String, Object> page = jdbc.queryForMap("SELECT merged_at, order_confirmed, version FROM setlist_pages WHERE event_id = ?", eventId);
        assertThat(page.get("merged_at")).isNotNull();
        assertThat(page.get("order_confirmed")).isEqualTo(false);
        Map<String, Object> revision = jdbc.queryForMap("SELECT source, editor_user_id FROM setlist_revisions WHERE event_id = ? AND version = 2", eventId);
        assertThat(revision.get("source")).isEqualTo("SETLISTFM_MERGE");
        assertThat(revision.get("editor_user_id")).isNull();
        assertThat(applicationEvents.stream(SetlistMerged.class).count()).isEqualTo(1);
        verify(alertPort, times(1)).send(anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_runs WHERE source = 'SETLISTFM' AND status = 'OK'", Integer.class)).isGreaterThanOrEqualTo(1);

        // E5
        assertThat(mergeUseCase.merge(eventId)).isFalse();
        assertThat(revisionCount(eventId)).isEqualTo(2);
        assertThat(applicationEvents.stream(SetlistMerged.class).count()).isEqualTo(1);
        verify(alertPort, times(1)).send(anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setlist_unmatched_names", Integer.class)).isEqualTo(1);

        // 관리자 수동 병합도 같은 결과. 응답에 notOnSetlistFm 이 A 에만 붙는다
        mvc.perform(post("/api/v1/admin/events/{id}/setlist/merge", eventId).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(false));
        mvc.perform(get("/api/v1/events/{id}/setlist", eventId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].notOnSetlistFm").value(false))
                .andExpect(jsonPath("$.data.entries[2].songId").value(a))
                .andExpect(jsonPath("$.data.entries[2].notOnSetlistFm").value(true));
    }

    @Test
    @DisplayName("E9: 미매칭 곡명을 관리자가 곡에 연결하면 별칭이 붙고 재병합으로 그 곡이 setlist.fm 순서에 들어간다")
    void link_unmatched_then_remerge_adds_song() throws Exception {
        mergeUseCase.merge(eventId);
        Long x = newSong("미지의 곡", null, "vidX");
        long unmatchedId = jdbc.queryForObject("SELECT id FROM setlist_unmatched_names WHERE event_id = ?", Long.class, eventId);

        mvc.perform(get("/api/v1/admin/songs/unmatched").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(unmatchedId))
                .andExpect(jsonPath("$.data[0].eventId").value(eventId))
                .andExpect(jsonPath("$.data[0].setlistFmName").value("Unknown Song"));
        mvc.perform(post("/api/v1/admin/songs/unmatched/{id}", unmatchedId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"songId\":" + x + "}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT alias FROM song_aliases WHERE song_id = ?", String.class, x)).containsExactly("unknown song");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setlist_unmatched_names", Integer.class)).isZero();
        assertThat(itemSongIds(eventId)).containsExactly(b, d, x, a);
        assertThat(revisionCount(eventId)).isEqualTo(3);
        assertThat(applicationEvents.stream(SetlistMerged.class).count()).isEqualTo(2);
        mvc.perform(post("/api/v1/admin/songs/unmatched/{id}", unmatchedId).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"songId\":" + x + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNMATCHED_NAME_NOT_FOUND"));
    }

    @Test
    @DisplayName("미매칭 연결 뒤 재병합의 setlist.fm 호출이 실패해도 별칭과 미매칭 행 삭제는 커밋된다")
    void link_keeps_alias_when_remerge_fails() throws Exception {
        mergeUseCase.merge(eventId);
        Long x = newSong("미지의 곡", null, "vidX");
        long unmatchedId = jdbc.queryForObject("SELECT id FROM setlist_unmatched_names WHERE event_id = ?", Long.class, eventId);
        when(setlistFmPort.fetch("fm1")).thenThrow(new IllegalStateException("setlist.fm 장애"));

        mvc.perform(post("/api/v1/admin/songs/unmatched/{id}", unmatchedId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"songId\":" + x + "}"));

        assertThat(jdbc.queryForList("SELECT alias FROM song_aliases WHERE song_id = ?", String.class, x)).containsExactly("unknown song");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setlist_unmatched_names", Integer.class)).isZero();
        assertThat(revisionCount(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("병합 트랜잭션이 롤백되면 운영자 알림을 보내지 않는다 (알림은 커밋 뒤)")
    void merge_alert_only_after_commit() {
        tx.executeWithoutResult(status -> {
            assertThat(mergeUseCase.merge(eventId)).isTrue();
            status.setRollbackOnly();
        });
        verify(alertPort, never()).send(anyString());
        assertThat(revisionCount(eventId)).isEqualTo(1);

        assertThat(mergeUseCase.merge(eventId)).isTrue();
        verify(alertPort, times(1)).send(anyString());
    }

    @Test
    @DisplayName("setlist.fm 을 받아 맞는 곡이 하나라도 있으면 변경이 없어도 merged_at 을 찍고, 전부 미매칭이면 찍지 않는다")
    void marks_merged_even_without_change() {
        // 한 번 병합해 위키를 setlist.fm 과 같게 만든 뒤 merged_at 만 지운다
        when(setlistFmPort.fetch("fm1")).thenReturn(Optional.of(new FmSetlist(List.of("bad dreams", "LEGEND"))));
        assertThat(mergeUseCase.merge(eventId)).isTrue();
        jdbc.update("UPDATE setlist_pages SET merged_at = NULL WHERE event_id = ?", eventId);

        assertThat(mergeUseCase.merge(eventId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT merged_at FROM setlist_pages WHERE event_id = ?", java.sql.Timestamp.class, eventId)).isNotNull();

        Long other = newEventLinkedToSetlistFm("미매칭뿐인 공연", today.minusDays(2), "fm2");
        when(setlistFmPort.fetch("fm2")).thenReturn(Optional.of(new FmSetlist(List.of("Unknown Song"))));
        assertThat(mergeUseCase.merge(other)).isFalse();
        assertThat(jdbc.queryForObject("SELECT merged_at FROM setlist_pages WHERE event_id = ?", java.sql.Timestamp.class, other)).isNull();
    }

    @Test
    @DisplayName("곡에 별칭을 붙인 뒤 재병합하면 이제 매칭되는 미매칭 행은 지우고, 여전히 안 맞는 행은 남긴다")
    void remerge_after_alias_removes_resolved_unmatched() throws Exception {
        when(setlistFmPort.fetch("fm1")).thenReturn(Optional.of(
                new FmSetlist(List.of("bad dreams", "Unknown Song", "Another Song"))));
        mergeUseCase.merge(eventId);
        assertThat(jdbc.queryForList("SELECT raw_name FROM setlist_unmatched_names WHERE event_id = ?", String.class, eventId))
                .containsExactlyInAnyOrder("Unknown Song", "Another Song");

        // 관리자 연결 API 가 아니라 곡 등록·수정으로 별칭이 생긴 경우
        Long x = newSong("미지의 곡", "Unknown Song", "vidX");

        assertThat(mergeUseCase.merge(eventId)).isTrue();
        assertThat(itemSongIds(eventId)).containsExactly(b, x, a);
        assertThat(jdbc.queryForList("SELECT raw_name FROM setlist_unmatched_names WHERE event_id = ?", String.class, eventId))
                .containsExactly("Another Song");
    }

    @Test
    @DisplayName("E8: 3일 안의 빈 페이지 행사는 직전 병합 행사의 셋리를 SETLISTFM_SEED(in_setlistfm=false, editor 없음) 로 받는다. 이미 편집된 페이지·범위 밖 행사는 제외")
    void seed_from_previous_merged_event() throws Exception {
        mergeUseCase.merge(eventId);
        Long upcoming = newEvent("다음 공연", today.plusDays(2));
        Long edited = newEvent("이미 편집된 공연", today.plusDays(3));
        mvc.perform(put("/api/v1/events/{id}/setlist", edited).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a))).andExpect(status().isOk());
        Long farAway = newEvent("먼 공연", today.plusDays(10));

        assertThat(seedUseCase.seedUpcoming(today)).isEqualTo(1);

        assertThat(itemSongIds(upcoming)).containsExactly(b, d, a);
        assertThat(jdbc.queryForList("SELECT in_setlistfm FROM setlist_items WHERE event_id = ?", Boolean.class, upcoming)).containsOnly(false);
        Map<String, Object> revision = jdbc.queryForMap("SELECT source, editor_user_id, version FROM setlist_revisions WHERE event_id = ?", upcoming);
        assertThat(revision.get("source")).isEqualTo("SETLISTFM_SEED");
        assertThat(revision.get("editor_user_id")).isNull();
        assertThat(itemSongIds(edited)).containsExactly(a);
        assertThat(revisionCount(farAway)).isZero();
        // 두 번 돌려도 다시 시드하지 않는다
        assertThat(seedUseCase.seedUpcoming(today)).isZero();
        assertThat(revisionCount(upcoming)).isEqualTo(1);
    }

    @Test
    @DisplayName("시드는 행사마다 따로 커밋해 한 행사의 실패가 같은 날 다른 행사의 시드를 되돌리지 않는다")
    void seed_failure_is_isolated_per_event() throws Exception {
        mergeUseCase.merge(eventId);
        Long broken = newEvent("시드가 실패할 공연", today.plusDays(1));
        Long fine = newEvent("시드될 공연", today.plusDays(2));
        // broken 행사의 리비전 INSERT 만 DB 에서 실패시킨다. 바이너리 로그가 켜져 있어 트리거는 root 로 만든다
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("CREATE TRIGGER fail_seed_for_test BEFORE INSERT ON setlist_revisions FOR EACH ROW "
                    + "BEGIN IF NEW.event_id = " + broken + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test failure'; END IF; END");
            try {
                assertThat(seedUseCase.seedUpcoming(today)).isEqualTo(1);
            } finally {
                statement.execute("DROP TRIGGER IF EXISTS fail_seed_for_test");
            }
        }
        assertThat(revisionCount(broken)).isZero();
        assertThat(itemSongIds(fine)).containsExactly(b, d, a);
    }

    @Test
    @DisplayName("스케줄 병합 대상: setlist.fm 연결 + (미병합 또는 최근 14일). 셋리가 아직 없으면(empty) 변경 없이 OK 로 기록")
    void merge_due_selection_and_missing_setlist() throws Exception {
        Long old = newEventLinkedToSetlistFm("오래된 공연", today.minusDays(60), "fmOld");
        Long unlinked = newEvent("연결 없는 공연", today.minusDays(1));
        when(setlistFmPort.fetch("fmOld")).thenReturn(Optional.empty());

        assertThat(mergeUseCase.mergeDue(today)).isEqualTo(1);
        assertThat(revisionCount(eventId)).isEqualTo(2);
        assertThat(revisionCount(old)).isZero();
        assertThat(revisionCount(unlinked)).isZero();
        assertThat(mergeUseCase.merge(unlinked)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_runs WHERE source = 'SETLISTFM' AND summary LIKE '%셋리 없음%'", Integer.class)).isEqualTo(1);

        // old 는 이제 병합됨(merged_at) 이고 14일 밖이라 다음 스케줄에서 빠진다
        when(setlistFmPort.fetch("fmOld")).thenReturn(Optional.of(new FmSetlist(List.of("LEGEND"))));
        assertThat(mergeUseCase.merge(old)).isTrue();
        assertThat(mergeUseCase.mergeDue(today)).isZero();
    }
}
