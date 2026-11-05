package com.stagelog.setlist.infrastructure;

import com.stagelog.ops.application.AgentRequestUseCase;
import com.stagelog.ops.application.port.AgentActionHandler;
import com.stagelog.setlist.application.UnmatchedUseCase;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인된 MCP 조치 LINK_UNMATCHED_SONG: 관리자 미매칭 곡명 연결과 같은 경로(link)를 탄다. targetRef 는 미매칭 ID, payload.songId 는 곡 ID.
 */
@Component
@RequiredArgsConstructor
public class UnmatchedLinkActionHandler implements AgentActionHandler {

    private final UnmatchedUseCase unmatchedUseCase;

    @Override
    public String type() {
        return "LINK_UNMATCHED_SONG";
    }

    @Override
    public void execute(String targetRef, Map<String, Object> payload) {
        unmatchedUseCase.link(Long.valueOf(targetRef), AgentRequestUseCase.songId(payload));
    }
}
