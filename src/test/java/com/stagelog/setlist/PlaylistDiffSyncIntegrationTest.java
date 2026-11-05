package com.stagelog.setlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.integration.youtube.YouTubeClient;
import com.stagelog.setlist.application.QuotaScope;
import com.stagelog.setlist.application.port.PlaylistPort;
import com.stagelog.setlist.application.port.PlaylistPort.ItemSync;
import com.stagelog.setlist.application.port.PlaylistPort.Outcome;
import com.stagelog.setlist.domain.QuotaBucket;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import com.stagelog.setlist.infrastructure.YouTubePlaylistAdapter;
import com.stagelog.setlist.support.FakeYouTubeIntegrationTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Diff Sync 를 Fake YouTube 서버에 실제로 적용해 본다: 무작위 변경 1,000건 일치, 시나리오별 쓰기 상한, videoNotFound 건너뜀,
 * 반영 뒤 불일치(PARTIAL), 원장 상태(CONSUMED·UNKNOWN·RELEASED).
 */
class PlaylistDiffSyncIntegrationTest extends FakeYouTubeIntegrationTest {

    static final QuotaScope SCOPE = new QuotaScope(QuotaBucket.AUTO, 100, null, null);

    @Autowired
    PlaylistPort playlists;
    @Autowired
    YouTubeClient client;
    @Autowired
    YouTubeCredentialRepository credentialRepository;

    @BeforeEach
    void setUp() {
        FAKE.reset();
        connectChannel();
        resetQuota(1_000_000, 1_000_000);
    }

    @Test
    @DisplayName("무작위 셋리 변경 1,000건(삭제·추가·순서 변경·중복 영상)을 차례로 동기화하면 매번 Playlist 가 목표와 같다")
    void random_changes_always_match() {
        Random random = new Random(20261105);
        FAKE.seed("PLR", "무작위", List.of());
        List<String> target = new ArrayList<>();
        int mismatches = 0;
        long writes = 0;
        for (int n = 0; n < 1_000; n++) {
            target = mutate(target, random);
            ItemSync result = playlists.syncItems("PLR", target, SCOPE);
            writes += result.writes();
            if (result.outcome() != Outcome.OK || !FAKE.videoIds("PLR").equals(target)) {
                mismatches++;
            }
        }
        System.out.printf("[JAN-03] random syncs=1000 mismatches=%d itemWrites=%d%n", mismatches, writes);
        assertThat(mismatches).isZero();
    }

    @Test
    @DisplayName("20곡 시나리오별 쓰기: 변경 없음 0, 1곡 교체 2, 앵콜 추가 1, 마지막 곡 삭제 1, 두 곡 맞바꿈 2 이하")
    void scenario_writes_within_targets() {
        List<String> base = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            base.add(String.format("v%02d", i));
        }
        List<String> replaced = new ArrayList<>(base);
        replaced.set(9, "v21");
        List<String> encore = new ArrayList<>(base);
        encore.add("v01");
        List<String> swapped = new ArrayList<>(base);
        Collections.swap(swapped, 4, 14);
        assertWrites(base, base, 0);
        assertWrites(base, replaced, 2);
        assertWrites(base, encore, 1);
        assertWrites(base, base.subList(0, 19), 1);
        assertWrites(base, swapped, 2);
    }

    @Test
    @DisplayName("videoNotFound 영상은 그 항목만 건너뛰고 나머지는 목표 순서로 맞춘 뒤 OK, 건너뛴 영상 ID 를 돌려준다")
    void video_not_found_skips_only_that_item() {
        FAKE.seed("PLV", "t", List.of("A", "B", "C"));
        FAKE.missingVideo("X");

        ItemSync result = playlists.syncItems("PLV", List.of("X", "C", "A", "X", "B"), SCOPE);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.skipped()).containsExactly("X", "X");
        assertThat(FAKE.videoIds("PLV")).containsExactly("C", "A", "B");
    }

    @Test
    @DisplayName("반영 뒤 다시 읽은 목록이 1·2·4·8초 재조회 뒤에도 목표와 다르면 PARTIAL, 전체 재구성은 하지 않는다")
    void mismatch_after_apply_is_partial() {
        List<Long> waits = new ArrayList<>();
        YouTubePlaylistAdapter adapter = new YouTubePlaylistAdapter(client, credentialRepository, quota, true, waits::add);
        FAKE.seed("PLP", "t", List.of("A", "B", "C"));
        FAKE.ignoreWrites(true);

        ItemSync result = adapter.syncItems("PLP", List.of("A", "C", "B", "D"), SCOPE);

        assertThat(result.outcome()).isEqualTo(Outcome.PARTIAL);
        assertThat(waits).containsExactly(1_000L, 2_000L, 4_000L, 8_000L);
        // 이동 1 + 삽입 1 만 보냈다 (전체 재구성이면 삭제 3 + 삽입 4)
        assertThat(FAKE.itemWrites()).isEqualTo(2);
    }

    @Test
    @DisplayName("원장: 응답 받은 호출은 CONSUMED, 응답이 끊긴 쓰기는 UNKNOWN(사용으로 계산), 호출하지 않은 예약은 RELEASED. 예약 합계는 0 으로 돌아온다")
    void ledger_statuses() {
        FAKE.seed("PLL", "t", List.of("A"));
        FAKE.dropNextWriteResponses(1);

        assertThatThrownBy(() -> playlists.syncItems("PLL", List.of("A", "B", "C"), SCOPE)).isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode()).isEqualTo(ErrorCode.YOUTUBE_API_ERROR);

        assertThat(jdbc.queryForList("SELECT CONCAT(operation, ' ', status) FROM api_quota_ledger ORDER BY id", String.class))
                .containsExactly("LIST CONSUMED", "INSERT UNKNOWN", "INSERT RELEASED");
        assertThat(quotaColumn("used_units", "AUTO")).isEqualTo(51);
        assertThat(quotaColumn("reserved_units", "AUTO")).isZero();
    }

    private void assertWrites(List<String> current, List<String> target, int maxWrites) {
        String playlistId = "PLS" + System.nanoTime();
        FAKE.seed(playlistId, "t", current);
        FAKE.clearCalls();

        ItemSync result = playlists.syncItems(playlistId, target, SCOPE);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(FAKE.videoIds(playlistId)).isEqualTo(target);
        assertThat(FAKE.itemWrites()).as("target=%s", target).isLessThanOrEqualTo(maxWrites);
    }

    // 1~3번 무작위 편집: 삭제, 중복 가능한 삽입, 이동, 교체. 길이는 0~30
    private static List<String> mutate(List<String> list, Random random) {
        List<String> out = new ArrayList<>(list);
        int edits = 1 + random.nextInt(3);
        for (int e = 0; e < edits; e++) {
            int kind = random.nextInt(4);
            if (kind == 0 && !out.isEmpty()) {
                out.remove(random.nextInt(out.size()));
            } else if (kind == 1 && out.size() < 30) {
                out.add(random.nextInt(out.size() + 1), "v" + random.nextInt(12));
            } else if (kind == 2 && out.size() > 1) {
                String moved = out.remove(random.nextInt(out.size()));
                out.add(random.nextInt(out.size() + 1), moved);
            } else if (!out.isEmpty()) {
                out.set(random.nextInt(out.size()), "v" + random.nextInt(12));
            }
        }
        return out;
    }
}
