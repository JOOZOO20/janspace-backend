package com.janspace.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.common.exception.predefined.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/**
 * 전역 예외 핸들러가 exception.md §5 표대로 봉투를 만드는지 standalone MockMvc 로 검증한다 (Spring 컨텍스트 없음).
 */
class GlobalExceptionRestAdviceTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionRestAdvice())
                .build();
    }

    @Test
    void predefined_exception_becomes_404_envelope() throws Exception {
        mockMvc.perform(get("/throw/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_FOUND.getMessage()));
    }

    @Test
    void valid_failure_becomes_400_INVALID_INPUT() throws Exception {
        mockMvc.perform(post("/throw/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("name: ")));
    }

    @Test
    void unreadable_body_becomes_400_INVALID_INPUT() throws Exception {
        mockMvc.perform(post("/throw/valid").contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void unknown_runtime_exception_becomes_500_INTERNAL_ERROR() throws Exception {
        mockMvc.perform(get("/throw/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    record NameRequest(@NotBlank String name) {
    }

    @RestController
    @Validated
    static class ThrowingController {

        @GetMapping("/throw/not-found")
        void notFound() {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }

        @PostMapping("/throw/valid")
        void valid(@RequestBody @jakarta.validation.Valid NameRequest request) {
        }

        @GetMapping("/throw/boom")
        void boom() {
            throw new IllegalStateException("예상 못한 오류");
        }
    }
}
