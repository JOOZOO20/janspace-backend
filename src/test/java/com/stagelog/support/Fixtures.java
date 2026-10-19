package com.stagelog.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * src/test/resources/fixtures 아래의 기록된 외부 응답을 읽는다.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("픽스처가 없습니다: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("픽스처를 읽을 수 없습니다: " + path, e);
        }
    }
}
