package com.janspace.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * application.yml 의 Jackson 설정으로 만든 매퍼가 PATCH 의 세 경우(키 없음, null, 값)를 구분하는지 확인한다.
 * 기능 PATCH DTO 는 Optional 필드가 null 이면 "보내지 않음(유지)", Optional.empty() 면 "지우기"로 해석한다.
 */
@SpringBootTest(classes = JacksonAutoConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OptionalPatchDeserializationTest {

    record Patch(Optional<String> memo, Optional<Long> songId) {
    }

    @Autowired
    JsonMapper mapper;

    @Test
    void missing_key_is_null_so_the_field_is_kept() {
        Patch patch = mapper.readValue("{}", Patch.class);
        assertThat(patch.memo()).isNull();
        assertThat(patch.songId()).isNull();
    }

    @Test
    void explicit_null_is_empty_so_the_field_is_cleared() {
        Patch patch = mapper.readValue("{\"memo\":null,\"songId\":null}", Patch.class);
        assertThat(patch.memo()).isEmpty();
        assertThat(patch.songId()).isEmpty();
    }

    @Test
    void value_is_present() {
        Patch patch = mapper.readValue("{\"memo\":\"앙코르에서 울었다\",\"songId\":5}", Patch.class);
        assertThat(patch.memo()).contains("앙코르에서 울었다");
        assertThat(patch.songId()).contains(5L);
    }

    // 이 설정은 역직렬화 전용이다. 응답 직렬화 결과는 설정을 끈 매퍼와 같아야 한다
    @Test
    void serialization_is_not_affected() {
        JsonMapper without = mapper.rebuild().disable(DeserializationFeature.USE_NULL_FOR_MISSING_REFERENCE_VALUES).build();
        Patch patch = new Patch(Optional.empty(), Optional.of(5L));
        assertThat(mapper.isEnabled(DeserializationFeature.USE_NULL_FOR_MISSING_REFERENCE_VALUES)).isTrue();
        assertThat(mapper.writeValueAsString(patch)).isEqualTo(without.writeValueAsString(patch));
    }
}
