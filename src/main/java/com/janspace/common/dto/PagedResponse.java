package com.janspace.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;
import org.springframework.data.domain.Page;

/**
 * 페이지네이션 응답. Spring Data Page 를 프론트가 쓰는 평면 구조로 옮긴다 (swagger.md §4(c)).
 */
@Getter
@Schema(description = "페이지네이션 응답")
public final class PagedResponse<T> {

    @Schema(description = "현재 페이지의 항목 목록", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<T> content;

    @Schema(description = "현재 페이지 번호 (0부터 시작)", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int page;

    @Schema(description = "페이지 크기", example = "20", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int size;

    @Schema(description = "전체 항목 수", example = "137", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long totalElements;

    @Schema(description = "전체 페이지 수", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int totalPages;

    @Schema(description = "마지막 페이지 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean last;

    private PagedResponse(List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.last = last;
    }

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }
}
