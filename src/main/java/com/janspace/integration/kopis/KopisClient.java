package com.janspace.integration.kopis;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * KOPIS OpenAPI 클라이언트 (XML, 설계). 목록·상세·시설 조회와 포스터 다운로드.
 * 응답은 <dbs><db>…</db></dbs> 이며 db 가 하나면 객체, 여럿이면 배열로 읽히므로 둘 다 처리한다.
 */
@Slf4j
@Component
public class KopisClient {

    static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final int ROWS = 100;

    private final KopisProperties props;
    private final RestClient restClient;
    private final XmlMapper xml = XmlMapper.builder()
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .build();

    public KopisClient(KopisProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public boolean isEnabled() {
        return props.serviceKey() != null && !props.serviceKey().isBlank();
    }

    /** 공연목록. [from, to] 는 31일 이하, afterDate 는 그날 이후 등록·수정분만(증분), name 은 공연명 검색어. 첫 페이지 100건. */
    public List<KopisPerformance> list(LocalDate from, LocalDate to, String name, LocalDate afterDate) throws RestClientException {
        StringBuilder uri = new StringBuilder(props.baseUri())
                .append("/pblprfr?service={key}&stdate={from}&eddate={to}&cpage=1&rows=").append(ROWS)
                .append("&shprfnm={name}");
        List<Object> vars = new ArrayList<>(List.of(props.serviceKey(), YYYYMMDD.format(from), YYYYMMDD.format(to),
                name == null ? "" : name));
        if (afterDate != null) {
            uri.append("&afterdate={after}");
            vars.add(YYYYMMDD.format(afterDate));
        }
        byte[] body = restClient.get().uri(uri.toString(), vars.toArray()).retrieve().body(byte[].class);
        List<KopisPerformance> items = dbs(body).stream().map(n -> xml.treeToValue(n, KopisPerformance.class)).toList();
        log.info("[KOPIS 공연목록] from={}, to={}, name={}, after={}, 결과={}", from, to, name, afterDate, items.size());
        return items;
    }

    public Optional<KopisPerformanceDetail> detail(String mt20id) throws RestClientException {
        byte[] body = restClient.get().uri(props.baseUri() + "/pblprfr/{id}?service={key}", mt20id, props.serviceKey())
                .retrieve().body(byte[].class);
        return dbs(body).stream().findFirst().map(n -> xml.treeToValue(n, KopisPerformanceDetail.class));
    }

    public Optional<KopisFacility> facility(String mt10id) throws RestClientException {
        byte[] body = restClient.get().uri(props.baseUri() + "/prfplc/{id}?service={key}", mt10id, props.serviceKey())
                .retrieve().body(byte[].class);
        return dbs(body).stream().findFirst().map(n -> xml.treeToValue(n, KopisFacility.class));
    }

    /** 포스터 이미지 (bytes, Content-Type). 4xx·5xx·빈 본문은 empty. */
    public record Image(byte[] bytes, String contentType) {
    }

    public Optional<Image> downloadImage(String url) {
        try {
            ResponseEntity<byte[]> entity = restClient.get().uri(url).retrieve().toEntity(byte[].class);
            byte[] bytes = entity.getBody();
            if (bytes == null || bytes.length == 0) {
                return Optional.empty();
            }
            MediaType type = entity.getHeaders().getContentType();
            return Optional.of(new Image(bytes, type == null ? null : type.getType() + "/" + type.getSubtype()));
        } catch (HttpClientErrorException | org.springframework.web.client.HttpServerErrorException e) {
            log.warn("[KOPIS 포스터 다운로드 실패] url={}, status={}", url, e.getStatusCode());
            return Optional.empty();
        }
    }

    // <dbs> 아래 <db> 노드들. 응답이 비어 있거나 <db> 가 없으면 빈 목록
    private List<JsonNode> dbs(byte[] body) {
        if (body == null || body.length == 0) {
            return List.of();
        }
        JsonNode db = xml.readTree(body).path("db");
        if (db.isArray()) {
            List<JsonNode> nodes = new ArrayList<>();
            db.forEach(nodes::add);
            return nodes;
        }
        return db.isObject() ? List.of(db) : List.of();
    }
}
