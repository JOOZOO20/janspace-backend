package com.stagelog.setlist.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * YouTube Data API v3 재생목록 부분과 구글 토큰 엔드포인트를 흉내 내는 Fake 서버. Playlist 상태를 메모리에 두고 받은 요청을 순서대로 기록한다.
 * playlistItems.insert·update 의 position 은 결과 목록에서의 0 기반 위치이고, 범위를 벗어나면 실제 API 처럼 400 invalidPlaylistItemPosition 이다.
 * 장애 주입: quotaExceeded(모든 Data API 요청 403), videoNotFound(지정 영상 insert 404), 쓰기 무시(200 을 주고 반영 안 함), 응답 유실(반영 후 연결 끊기).
 */
public final class FakeYouTubeServer {

    public static final String DATA_API_PREFIX = "https://www.googleapis.com/youtube/v3";
    public static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    public record Call(String method, String resource, String op) {
        public boolean isItemWrite() {
            return resource.equals("playlistItems") && !op.equals("LIST");
        }

        public boolean isRead() {
            return op.equals("LIST");
        }
    }

    record Item(String id, String videoId) {
    }

    record Playlist(String title, List<Item> items) {
    }

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Playlist> playlists = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean quotaExceeded = new AtomicBoolean();
    private final AtomicBoolean ignoreWrites = new AtomicBoolean();
    private final AtomicInteger dropWriteResponses = new AtomicInteger();
    private final Set<String> missingVideos = ConcurrentHashMap.newKeySet();

    private FakeYouTubeServer(HttpServer server) {
        this.server = server;
    }

    public static FakeYouTubeServer start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeYouTubeServer fake = new FakeYouTubeServer(http);
            http.createContext("/", fake::handle);
            http.setExecutor(Executors.newFixedThreadPool(16));
            http.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 실제 API 주소로 나가는 요청의 host 를 이 서버로 바꾼다. 주소가 상수인 클라이언트도 그대로 붙일 수 있다
    public ClientHttpRequestInterceptor redirector() {
        return (request, body, execution) -> {
            String uri = request.getURI().toString();
            String rewritten = uri.startsWith(DATA_API_PREFIX) ? baseUrl() + "/youtube/v3" + uri.substring(DATA_API_PREFIX.length())
                    : uri.startsWith(TOKEN_URL) ? baseUrl() + "/token" + uri.substring(TOKEN_URL.length()) : uri;
            HttpRequest target = new HttpRequestWrapper(request) {
                @Override
                public URI getURI() {
                    return URI.create(rewritten);
                }
            };
            return execution.execute(target, body);
        };
    }

    public void seed(String playlistId, String title, List<String> videoIds) {
        List<Item> items = new ArrayList<>();
        videoIds.forEach(v -> items.add(new Item(nextItemId(), v)));
        playlists.put(playlistId, new Playlist(title, items));
    }

    public List<String> videoIds(String playlistId) {
        Playlist playlist = playlists.get(playlistId);
        synchronized (playlist) {
            return playlist.items().stream().map(Item::videoId).toList();
        }
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public long itemWrites() {
        return calls.stream().filter(Call::isItemWrite).count();
    }

    public long reads() {
        return calls.stream().filter(Call::isRead).count();
    }

    public void clearCalls() {
        calls.clear();
    }

    // 상태·기록·주입을 모두 처음으로
    public void reset() {
        playlists.clear();
        calls.clear();
        quotaExceeded.set(false);
        ignoreWrites.set(false);
        dropWriteResponses.set(0);
        missingVideos.clear();
    }

    public void quotaExceeded(boolean on) {
        quotaExceeded.set(on);
    }

    public void ignoreWrites(boolean on) {
        ignoreWrites.set(on);
    }

    public void dropNextWriteResponses(int count) {
        dropWriteResponses.set(count);
    }

    public void missingVideo(String videoId) {
        missingVideos.add(videoId);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (path.equals("/token")) {
                respond(exchange, 200, Map.of("access_token", "fake-access-token", "expires_in", 3599));
                return;
            }
            String resource = path.substring(path.lastIndexOf('/') + 1);
            String op = switch (method) {
                case "GET" -> "LIST";
                case "POST" -> "INSERT";
                case "PUT" -> "UPDATE";
                case "DELETE" -> "DELETE";
                default -> method;
            };
            calls.add(new Call(method, resource, op));
            if (quotaExceeded.get()) {
                error(exchange, 403, "quotaExceeded");
                return;
            }
            switch (resource) {
                case "playlists" -> playlists(exchange, op, query, body);
                case "playlistItems" -> playlistItems(exchange, op, query, body);
                default -> error(exchange, 404, "notFound");
            }
        }
    }

    private void playlists(HttpExchange exchange, String op, Map<String, String> query, byte[] body) throws IOException {
        if (op.equals("LIST")) {
            Playlist playlist = playlists.get(query.get("id"));
            List<Object> items = playlist == null ? List.of() : List.of(Map.of("id", query.get("id"), "snippet", Map.of("title", playlist.title())));
            respond(exchange, 200, Map.of("items", items));
            return;
        }
        Map<String, Object> request = read(body);
        String title = (String) snippet(request).get("title");
        String id = "PL" + nextItemId();
        playlists.put(id, new Playlist(title, new ArrayList<>()));
        respond(exchange, 200, Map.of("id", id, "snippet", Map.of("title", title)));
    }

    private void playlistItems(HttpExchange exchange, String op, Map<String, String> query, byte[] body) throws IOException {
        if (op.equals("LIST")) {
            list(exchange, query);
            return;
        }
        if (op.equals("DELETE")) {
            Playlist playlist = owner(query.get("id"));
            if (playlist == null) {
                error(exchange, 404, "playlistItemNotFound");
                return;
            }
            if (!ignoreWrites.get()) {
                synchronized (playlist) {
                    playlist.items().removeIf(i -> i.id().equals(query.get("id")));
                }
            }
            if (!dropResponse()) {
                exchange.sendResponseHeaders(204, -1);
            }
            return;
        }
        Map<String, Object> request = read(body);
        Map<String, Object> snippet = snippet(request);
        Playlist playlist = playlists.get((String) snippet.get("playlistId"));
        if (playlist == null) {
            error(exchange, 404, "playlistNotFound");
            return;
        }
        @SuppressWarnings("unchecked")
        String videoId = (String) ((Map<String, Object>) snippet.get("resourceId")).get("videoId");
        Integer position = snippet.get("position") == null ? null : ((Number) snippet.get("position")).intValue();
        Item item;
        synchronized (playlist) {
            List<Item> items = playlist.items();
            if (op.equals("INSERT")) {
                if (missingVideos.contains(videoId)) {
                    error(exchange, 404, "videoNotFound");
                    return;
                }
                item = new Item(nextItemId(), videoId);
                int at = position == null ? items.size() : position;
                if (at < 0 || at > items.size()) {
                    error(exchange, 400, "invalidPlaylistItemPosition");
                    return;
                }
                if (!ignoreWrites.get()) {
                    items.add(at, item);
                }
            } else {
                String itemId = (String) request.get("id");
                int from = indexOf(items, itemId);
                if (from < 0) {
                    error(exchange, 404, "playlistItemNotFound");
                    return;
                }
                item = items.get(from);
                int at = position == null ? from : position;
                if (at < 0 || at > items.size() - 1) {
                    error(exchange, 400, "invalidPlaylistItemPosition");
                    return;
                }
                if (!ignoreWrites.get()) {
                    items.remove(from);
                    items.add(at, item);
                }
            }
        }
        if (!dropResponse()) {
            respond(exchange, 200, itemJson(snippet.get("playlistId").toString(), item, position == null ? -1 : position));
        }
    }

    private void list(HttpExchange exchange, Map<String, String> query) throws IOException {
        String playlistId = query.get("playlistId");
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            error(exchange, 404, "playlistNotFound");
            return;
        }
        int size = Integer.parseInt(query.getOrDefault("maxResults", "5"));
        String token = query.get("pageToken");
        int start = token == null || token.isBlank() ? 0 : Integer.parseInt(token);
        List<Object> page = new ArrayList<>();
        int total;
        synchronized (playlist) {
            List<Item> items = playlist.items();
            total = items.size();
            for (int i = start; i < Math.min(total, start + size); i++) {
                page.add(itemJson(playlistId, items.get(i), i));
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        if (start + size < total) {
            res.put("nextPageToken", String.valueOf(start + size));
        }
        res.put("items", page);
        respond(exchange, 200, res);
    }

    private Playlist owner(String itemId) {
        for (Playlist playlist : playlists.values()) {
            synchronized (playlist) {
                if (indexOf(playlist.items(), itemId) >= 0) {
                    return playlist;
                }
            }
        }
        return null;
    }

    private static int indexOf(List<Item> items, String itemId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(itemId)) {
                return i;
            }
        }
        return -1;
    }

    // 쓰기는 반영한 뒤 응답을 보내지 않고 연결을 닫는다 (클라이언트에서는 결과를 모르는 I/O 오류)
    private boolean dropResponse() {
        return dropWriteResponses.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    private Map<String, Object> itemJson(String playlistId, Item item, int position) {
        Map<String, Object> snippet = new HashMap<>();
        snippet.put("playlistId", playlistId);
        snippet.put("position", position);
        snippet.put("resourceId", Map.of("kind", "youtube#video", "videoId", item.videoId()));
        return Map.of("id", item.id(), "snippet", snippet);
    }

    private String nextItemId() {
        return "item" + ids.incrementAndGet();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(byte[] body) {
        return json.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snippet(Map<String, Object> request) {
        return (Map<String, Object>) request.getOrDefault("snippet", Map.of());
    }

    private void error(HttpExchange exchange, int status, String reason) throws IOException {
        respond(exchange, status, Map.of("error", Map.of("code", status, "message", reason,
                "errors", List.of(Map.of("reason", reason, "domain", "youtube")))));
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        UriComponentsBuilder.fromUriString("?" + raw).build().getQueryParams()
                .forEach((k, v) -> out.put(k, v.isEmpty() || v.get(0) == null ? "" : URLDecoder.decode(v.get(0), StandardCharsets.UTF_8)));
        return out;
    }
}
