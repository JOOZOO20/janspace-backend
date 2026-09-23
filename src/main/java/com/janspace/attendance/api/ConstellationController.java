package com.janspace.attendance.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.attendance.application.AttendanceQuery;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import com.janspace.event.application.EventView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 팬 전체의 하늘 (공개). 행사 하나 = 별자리 하나, 별 수만 낸다.
 */
@Tag(name = "별자리 (Constellation)", description = "행사별 기록자 수 (공개, 익명)")
@RestController
@RequestMapping(V1_BASE)
@RequiredArgsConstructor
public class ConstellationController {

    private final AttendanceQuery attendanceQuery;
    private final EventQuery eventQuery;

    @Operation(summary = "별자리 목록", description = "기록이 1건 이상인 행사를 날짜 내림차순으로 냅니다.")
    @GetMapping("/constellations")
    public ResponseDTO<List<ConstellationResponse>> list() {
        Map<Long, Long> counts = attendanceQuery.allConstellationCounts();
        return ResponseDTO.okWithData(assemble(counts));
    }

    @Operation(summary = "행사 별자리", description = "행사 하나의 별 수. 없거나 삭제된 행사는 404 EVENT_NOT_FOUND.")
    @GetMapping("/events/{id}/constellation")
    public ResponseDTO<ConstellationResponse> one(@Parameter(description = "행사 ID", example = "12") @PathVariable Long id) {
        EventView view = eventQuery.view(id);
        EventSnapshot snapshot = eventQuery.find(id).orElse(null);
        return ResponseDTO.okWithData(ConstellationResponse.from(view, snapshot, attendanceQuery.constellationCount(id)));
    }

    private List<ConstellationResponse> assemble(Map<Long, Long> counts) {
        if (counts.isEmpty()) {
            return List.of();
        }
        Map<Long, EventSnapshot> snapshots = eventQuery.findByIds(counts.keySet()).stream()
                .collect(Collectors.toMap(EventSnapshot::id, Function.identity()));
        return eventQuery.viewsByIds(counts.keySet()).stream()
                .sorted(Comparator.comparing(EventView::eventDate).reversed().thenComparing(EventView::id, Comparator.reverseOrder()))
                .map(v -> ConstellationResponse.from(v, snapshots.get(v.id()), counts.getOrDefault(v.id(), 0L)))
                .toList();
    }
}
