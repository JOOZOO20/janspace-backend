package com.janspace.event.application;

import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * SourceSnapshot <-> JSON (event_sources / event_match_candidates / event_discoveries 의 raw 컬럼).
 */
@Component
public class SourceSnapshotCodec {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String write(SourceSnapshot snapshot) {
        return mapper.writeValueAsString(snapshot);
    }

    public SourceSnapshot read(String raw) {
        return mapper.readValue(raw, SourceSnapshot.class);
    }
}
