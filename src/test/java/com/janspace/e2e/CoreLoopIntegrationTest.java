package com.janspace.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;

/**
 * 코어 루프 E2E: 로그인 -> 동의 -> 출발지 -> 관리자 행사 생성 -> 갈래요 -> 기록(감정 3개 + 곡) ->
 * 거리·통계·별자리 -> 셋리 위키에 곡 반영(BestSongChosen) -> 포토카드 연결 -> 탈퇴 정리. 전 단계를 HTTP 로 검증한다.
 */
class CoreLoopIntegrationTest extends E2eIntegrationTest {

    static final String KSPO_VENUE = "{\"name\":{\"original\":\"KSPO DOME\"},\"city\":{\"original\":\"서울\"},\"countryCode\":\"KR\","
            + "\"location\":{\"lat\":37.5199,\"lng\":127.1233}}";

    @Test
    @DisplayName("로그인부터 탈퇴까지: 갈래요·기록·거리·별·셋리·포토카드가 한 흐름으로 이어지고 탈퇴 시 정리된다")
    void core_loop() throws Exception {
        // 1. 로그인 -> 동의 -> 출발지 (PATCH /me 만이 출발지 입력 경로, 설계)
        Session fan = loginAndConsent("naver-userinfo-n1.json");
        mvc.perform(patch("/api/v1/me").with(bearer(fan)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departure\":{\"name\":\"서울 중구\",\"countryCode\":\"KR\",\"lat\":37.5665,\"lng\":126.9780}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.departure.name").value("서울 중구"));

        // 2. 관리자: 곡 등록, 지난 행사(어제)·다음 행사 생성 (공연장 좌표·종료 시각 포함)
        Session admin = loginAsAdmin("naver-userinfo-n2.json");
        long song = json(mvc.perform(put("/api/v1/admin/songs").with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":{\"original\":\"주저하는 연인들을 위해\"},\"aliases\":[\"For Lovers Who Hesitate\"]}"))
                .andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
        long other = json(mvc.perform(put("/api/v1/admin/songs").with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":{\"original\":\"뜨거운 여름밤은 가고 남은 건 볼품없지만\"}}"))
                .andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
        mvc.perform(get("/api/v1/songs").param("q", "hesitate"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(song));

        long past = createEvent(admin, "지난 공연", TODAY.minusDays(1));
        long future = createEvent(admin, "다음 공연", TODAY.plusDays(10));

        // 3. 갈래요: my=GOING, 별은 아직 0
        mvc.perform(post("/api/v1/events/{id}/going", future).with(bearer(fan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.my.status").value("GOING"))
                .andExpect(jsonPath("$.data.starCount").value(0));
        mvc.perform(post("/api/v1/records").with(bearer(fan)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(future, "[\"HAPPY\"]", false, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_ENDED"));

        // 4. 지난 행사 기록: 감정 3개 + 최고의 곡 -> 거리·누적
        DocumentContext record = json(mvc.perform(post("/api/v1/records").with(bearer(fan)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(past, "[\"HAPPY\",\"EXCITED\",\"FLUTTER\"]", false, null, song)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emotions.length()").value(3))
                .andExpect(jsonPath("$.data.songId").value(song))
                .andExpect(jsonPath("$.data.distanceKm").value(27.6))
                .andExpect(jsonPath("$.data.cumulativeKm").value(27.6))
                .andReturn());
        long recordId = record.read("$.data.id", Long.class);

        mvc.perform(get("/api/v1/records").with(bearer(fan)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(recordId))
                .andExpect(jsonPath("$.data[0].distanceKm").value(27.6))
                .andExpect(jsonPath("$.data[0].cumulativeKm").value(27.6));
        mvc.perform(get("/api/v1/me/stats").with(bearer(fan)))
                .andExpect(jsonPath("$.data.totalKm").value(27.6))
                .andExpect(jsonPath("$.data.recordCount").value(1))
                .andExpect(jsonPath("$.data.altitude.passedKm").value(10))
                .andExpect(jsonPath("$.data.altitude.nextKm").value(100));
        mvc.perform(get("/api/v1/constellations"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].event.id").value(past))
                .andExpect(jsonPath("$.data[0].starCount").value(1));
        mvc.perform(get("/api/v1/events/{id}", past).with(bearer(fan)))
                .andExpect(jsonPath("$.data.starCount").value(1))
                .andExpect(jsonPath("$.data.my.status").value("RECORDED"))
                .andExpect(jsonPath("$.data.my.recordId").value(recordId));

        // 5. 셋리: 기록에서 고른 곡이 BestSongChosen 으로 위키에 붙고, 팬이 곡을 하나 더 편집한다 (editor 내부 저장)
        mvc.perform(get("/api/v1/events/{id}/setlist", past))
                .andExpect(jsonPath("$.data.entries.length()").value(1))
                .andExpect(jsonPath("$.data.entries[0].songId").value(song))
                .andExpect(jsonPath("$.data.entries[0].notOnSetlistFm").value(true));
        mvc.perform(put("/api/v1/events/{id}/setlist", past).with(bearer(fan)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"songId\":" + song + ",\"order\":1},{\"songId\":" + other + ",\"order\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.entries.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setlist_revisions WHERE event_id = ? AND editor_user_id = ?",
                Integer.class, past, fan.userId())).isEqualTo(1);

        // 6. 포토카드를 기록에 연결 -> GET /records/{id} 에 photocardId
        long cardId = json(mvc.perform(multipart(HttpMethod.POST, "/api/v1/photocards")
                        .file(new MockMultipartFile("image", "photo.png", "image/png", png()))
                        .part(new MockPart("meta", ("{\"linkedRecordId\":" + recordId + "}").getBytes(StandardCharsets.UTF_8)))
                        .with(bearer(fan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedRecordId").value(recordId))
                .andReturn()).read("$.data.id", Long.class);
        mvc.perform(get("/api/v1/records/{id}", recordId).with(bearer(fan)))
                .andExpect(jsonPath("$.data.photocardId").value(cardId));

        // 7. 탈퇴: 이후 요청은 401, 기록·포토카드는 소프트 삭제, 셋리 편집자 ID 는 지워지고 위키 항목은 남는다
        mvc.perform(delete("/api/v1/me").with(bearer(fan))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").with(bearer(fan))).andExpect(status().isUnauthorized());
        // 아직 만료 전인 토큰의 쓰기 요청은 동의 게이트가 401 로 막는다. 공개 GET(기록 목록 등)은 사용자 존재를 보지 않는다
        mvc.perform(post("/api/v1/events/{id}/going", future).with(bearer(fan))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/records").with(bearer(fan))).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendances WHERE user_id = ? AND deleted_at IS NULL",
                Integer.class, fan.userId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photocards WHERE user_id = ? AND deleted_at IS NULL",
                Integer.class, fan.userId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setlist_revisions WHERE event_id = ? AND editor_user_id IS NOT NULL",
                Integer.class, past)).isZero();
        mvc.perform(get("/api/v1/events/{id}/setlist", past)).andExpect(jsonPath("$.data.entries.length()").value(2));
        mvc.perform(get("/api/v1/events/{id}", past)).andExpect(jsonPath("$.data.starCount").value(0));
    }

    private long createEvent(Session admin, String name, java.time.LocalDate date) throws Exception {
        return json(mvc.perform(post("/api/v1/admin/events").with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":{\"original\":\"" + name + "\"},\"kind\":\"CONCERT\",\"date\":\"" + date
                                + "\",\"startTime\":\"19:00\",\"endTime\":\"21:00\",\"venue\":" + KSPO_VENUE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETE"))
                .andReturn()).read("$.data.id", Long.class);
    }

    static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
}
