package com.janspace.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;
import com.janspace.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class ResponseDTOTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void ok_has_code_OK_and_null_fields() throws Exception {
        assertThat(mapper.writeValueAsString(ResponseDTO.ok()))
                .isEqualTo("{\"code\":\"OK\",\"data\":null,\"message\":null}");
    }

    @Test
    void okWithData_and_okWithMessageAndData() {
        ResponseDTO<String> data = ResponseDTO.okWithData("x");
        assertThat(data.getCode()).isEqualTo("OK");
        assertThat(data.getData()).isEqualTo("x");
        assertThat(data.getMessage()).isNull();

        ResponseDTO<Integer> both = ResponseDTO.okWithMessageAndData("저장됨", 1);
        assertThat(both.getMessage()).isEqualTo("저장됨");
        assertThat(both.getData()).isEqualTo(1);
    }

    @Test
    void error_uses_error_code_name_and_message() {
        ResponseDTO<Void> error = ResponseDTO.error(ErrorCode.NOT_FOUND);
        assertThat(error.getCode()).isEqualTo("NOT_FOUND");
        assertThat(error.getMessage()).isEqualTo(ErrorCode.NOT_FOUND.getMessage());
        assertThat(error.getData()).isNull();

        assertThat(ResponseDTO.errorWithMessage(ErrorCode.CONFLICT, "커스텀").getMessage()).isEqualTo("커스텀");
    }
}
