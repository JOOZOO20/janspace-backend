package com.stagelog.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.common.exception.predefined.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 전역 예외 핸들러가 모든 오류를 같은 봉투로 만드는지 standalone MockMvc 로 검증한다 (Spring 컨텍스트 없음).
 */
@ExtendWith(OutputCaptureExtension.class)
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
                .andExpect(jsonPath("$.message").value(startsWith("name: ")));
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

    @Test
    void path_type_mismatch_becomes_400_with_parameter_name() throws Exception {
        mockMvc.perform(get("/throw/items/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(startsWith("id: ")));
    }

    @Test
    void bad_enum_query_becomes_400_with_allowed_values() throws Exception {
        mockMvc.perform(get("/throw/tab").param("tab", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(containsString("UPCOMING")));
    }

    @Test
    void missing_query_parameter_becomes_400() throws Exception {
        mockMvc.perform(get("/throw/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(startsWith("q: ")));
    }

    @Test
    void missing_multipart_part_becomes_400() throws Exception {
        mockMvc.perform(multipart("/throw/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(startsWith("file: ")));
    }

    @Test
    void unsupported_method_becomes_405_with_allow_header() throws Exception {
        mockMvc.perform(delete("/throw/valid"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupported_content_type_becomes_415() throws Exception {
        mockMvc.perform(post("/throw/valid").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void upload_over_limit_becomes_413() throws Exception {
        mockMvc.perform(get("/throw/too-large"))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));
    }

    @Test
    void unknown_path_becomes_404_envelope() throws Exception {
        mockMvc.perform(get("/nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void wrapped_upstream_failure_logs_the_cause_stack(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/throw/upstream"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("OAUTH_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.OAUTH_PROVIDER_ERROR.getMessage()));
        assertThat(output.getAll()).contains("Caused by: " + RestClientException.class.getName() + ": upstream-down");
    }

    enum Tab {
        UPCOMING, PAST
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

        @GetMapping("/throw/items/{id}")
        void item(@PathVariable Long id) {
        }

        @GetMapping("/throw/tab")
        void tab(@RequestParam Tab tab) {
        }

        @GetMapping("/throw/search")
        void search(@RequestParam String q) {
        }

        @PostMapping("/throw/upload")
        void upload(@RequestPart MultipartFile file) {
        }

        @GetMapping("/throw/too-large")
        void tooLarge() {
            throw new MaxUploadSizeExceededException(10L * 1024 * 1024);
        }

        @GetMapping("/throw/upstream")
        void upstream() {
            throw new ApplicationException(ErrorCode.OAUTH_PROVIDER_ERROR, new RestClientException("upstream-down"));
        }
    }
}
