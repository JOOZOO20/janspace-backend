package com.stagelog.integration.deepl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.infrastructure.DeepLTranslationAdapter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * DeepL 번역 픽스처 매핑 (실키 없음 — 실연동 미확인).
 */
class DeepLClientTest {

    @Test
    @DisplayName("DeepL-Auth-Key 헤더로 text/source_lang/target_lang JSON 을 POST 하고 translations[].text 를 돌려준다")
    void posts_and_parses_fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(DeepLClient.TRANSLATE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "DeepL-Auth-Key k"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text[0]").value("잔나비 단독 공연"))
                .andExpect(jsonPath("$.source_lang").value("KO"))
                .andExpect(jsonPath("$.target_lang").value("EN"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/deepl/translate.json"), MediaType.APPLICATION_JSON));

        List<String> out = new DeepLClient(builder, "k").translate(List.of("잔나비 단독 공연"), "KO", "EN");

        assertThat(out).containsExactly("Jannabi Solo Concert");
        server.verify();
    }

    @Test
    @DisplayName("어댑터: 로케일마다 한 번씩 호출하고 원어 로케일은 건너뛴다. ZH_TW 는 ZH-HANT")
    void adapter_maps_locales() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String target : List.of("EN", "ZH-HANT", "JA")) {
            server.expect(requestTo(DeepLClient.TRANSLATE_URL))
                    .andExpect(jsonPath("$.target_lang").value(target))
                    .andRespond(withSuccess("{\"translations\":[{\"text\":\"" + target + "-text\"}]}", MediaType.APPLICATION_JSON));
        }
        DeepLTranslationAdapter adapter = new DeepLTranslationAdapter(new DeepLClient(builder, "k"));

        // EnumSet 순서 = KO, EN, ZH_TW, JA 이므로 기대 요청 순서와 같다
        Map<SupportedLocale, String> drafts = adapter.draft("잔나비", SupportedLocale.KO, EnumSet.allOf(SupportedLocale.class));

        assertThat(drafts).containsEntry(SupportedLocale.EN, "EN-text")
                .containsEntry(SupportedLocale.ZH_TW, "ZH-HANT-text")
                .containsEntry(SupportedLocale.JA, "JA-text")
                .doesNotContainKey(SupportedLocale.KO);
        server.verify();
    }

    @Test
    @DisplayName("키가 비어 있으면 요청 없이 빈 결과")
    void skips_without_key() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThat(new DeepLTranslationAdapter(new DeepLClient(builder, "")).draft("x", SupportedLocale.KO, EnumSet.of(SupportedLocale.EN))).isEmpty();
        server.verify();
    }
}
