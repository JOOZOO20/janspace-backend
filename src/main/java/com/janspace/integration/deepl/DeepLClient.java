package com.janspace.integration.deepl;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * DeepL 번역 (Free 플랜 엔드포인트). 요청당 대상 언어 하나. 키가 비어 있으면 호출하지 않고 empty 를 준다.
 * 언어 코드는 DeepL 표기 (source: KO/EN/JA/ZH, target: KO/EN/JA/ZH-HANT).
 */
@Slf4j
@Component
public class DeepLClient {

    static final String TRANSLATE_URL = "https://api-free.deepl.com/v2/translate";

    private final RestClient restClient;
    private final String apiKey;

    public DeepLClient(RestClient.Builder restClientBuilder, @Value("${janspace.deepl.api-key:}") String apiKey) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isEnabled() {
        return !apiKey.isEmpty();
    }

    public record Translation(String text) {
    }

    public List<String> translate(List<String> texts, String sourceLang, String targetLang) {
        if (!isEnabled()) {
            log.info("[DeepL 번역 건너뜀] DEEPL_API_KEY 미설정");
            return List.of();
        }
        record Response(List<Translation> translations) {
        }
        // 외부 호출
        Response response = restClient.post()
                .uri(TRANSLATE_URL)
                .header(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", texts, "source_lang", sourceLang, "target_lang", targetLang))
                .retrieve()
                .body(Response.class);
        return response == null || response.translations() == null ? List.of()
                : response.translations().stream().map(Translation::text).toList();
    }
}
