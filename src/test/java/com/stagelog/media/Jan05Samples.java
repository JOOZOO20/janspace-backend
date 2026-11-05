package com.stagelog.media;

import com.stagelog.support.ProjectPaths;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JAN-05 샘플 원본 100장 (리포트의 "샘플 원본 100장 구성"). Lorem Picsum 목록 1쪽(limit 100) 순서대로 받아 규격을 맞춘다.
 * 1~50 포스터 1000x1414 JPEG, 51~70 앨범 1400x1400 JPEG, 71~90 포토카드 1080x1350 PNG, 91~100 네컷 1080x3240 PNG(받은 JPEG 를 PNG 로 다시 저장).
 * 파일은 커밋하지 않고 JAN05_SAMPLES_DIR(기본 build/jan05-samples)에 캐시한다. manifest(raw/JAN-05-samples.tsv)가 있으면 sha256 이 같아야 쓴다.
 */
final class Jan05Samples {

    static final Path MANIFEST = ProjectPaths.projectRoot().resolve("docs/measurements/raw/JAN-05-samples.tsv");
    static final String LIST_URL = "https://picsum.photos/v2/list?page=1&limit=100";

    record Sample(int n, String id, String author, String url, String role, int width, int height, String format, byte[] bytes) {

        String contentType() {
            return format.equals("png") ? "image/png" : "image/jpeg";
        }
    }

    private Jan05Samples() {
    }

    static List<Sample> load() throws IOException, InterruptedException {
        Path dir = Path.of(System.getenv().getOrDefault("JAN05_SAMPLES_DIR",
                ProjectPaths.projectRoot().resolve("build/jan05-samples").toString()));
        Files.createDirectories(dir);
        Map<Integer, String> expected = expectedSha();
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        JsonNode list = null;
        List<Sample> samples = new ArrayList<>();
        List<String> manifest = new ArrayList<>(List.of("n\tpicsum_id\tauthor\tsource_url\trole\twidth\theight\tformat\tbytes\tsha256"));
        for (int n = 1; n <= 100; n++) {
            String role = n <= 50 ? "poster" : n <= 70 ? "album" : n <= 90 ? "photocard" : "fourcut";
            int width = n <= 50 ? 1000 : n <= 70 ? 1400 : 1080;
            int height = n <= 50 ? 1414 : n <= 70 ? 1400 : n <= 90 ? 1350 : 3240;
            String format = n <= 70 ? "jpg" : "png";
            Path meta = dir.resolve(n + ".meta");
            Path file = dir.resolve(n + "." + format);
            if (!Files.exists(file) || !Files.exists(meta)) {
                if (list == null) {
                    list = JsonMapper.builder().build().readTree(get(http, LIST_URL));
                }
                JsonNode item = list.get(n - 1);
                String id = item.get("id").asString();
                String url = "https://picsum.photos/id/" + id + "/" + width + "/" + height;
                byte[] jpeg = get(http, url);
                byte[] bytes = format.equals("png") ? toPng(jpeg) : jpeg;
                Files.write(file, bytes);
                Files.writeString(meta, id + "\t" + item.get("author").asString() + "\t" + url, StandardCharsets.UTF_8);
            }
            String[] m = Files.readString(meta, StandardCharsets.UTF_8).split("\t");
            byte[] bytes = Files.readAllBytes(file);
            String sha = sha256(bytes);
            if (expected.containsKey(n) && !expected.get(n).equals(sha)) {
                throw new IllegalStateException("샘플 " + n + " 의 sha256 이 manifest 와 다르다. 캐시를 지우지 말고 원인을 확인할 것: " + file);
            }
            samples.add(new Sample(n, m[0], m[1], m[2], role, width, height, format, bytes));
            manifest.add(String.join("\t", String.valueOf(n), m[0], m[1], m[2], role, String.valueOf(width), String.valueOf(height), format,
                    String.valueOf(bytes.length), sha));
        }
        if (expected.isEmpty()) {
            Files.createDirectories(MANIFEST.getParent());
            Files.write(MANIFEST, manifest, StandardCharsets.UTF_8);
        }
        return samples;
    }

    private static Map<Integer, String> expectedSha() throws IOException {
        Map<Integer, String> expected = new HashMap<>();
        if (Files.exists(MANIFEST)) {
            List<String> lines = Files.readAllLines(MANIFEST, StandardCharsets.UTF_8);
            for (String line : lines.subList(1, lines.size())) {
                String[] cols = line.split("\t");
                expected.put(Integer.parseInt(cols[0]), cols[9]);
            }
        }
        return expected;
    }

    private static byte[] get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("GET " + response.statusCode() + " " + url);
        }
        return response.body();
    }

    private static byte[] toPng(byte[] jpeg) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
