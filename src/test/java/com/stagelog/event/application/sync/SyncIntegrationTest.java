package com.stagelog.event.application.sync;

import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventDiscoveryRepository;
import com.stagelog.event.domain.EventMatchCandidateRepository;
import com.stagelog.event.domain.EventSourceChangeRepository;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.Venue;
import com.stagelog.event.domain.VenueRepository;
import com.stagelog.event.support.EventIntegrationTest;
import com.stagelog.ops.domain.SyncRun;
import com.stagelog.ops.domain.SyncRunRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.test.web.client.response.MockRestResponseCreators;

/**
 * 동기화 통합 테스트 기반: 제공자 HTTP 는 MockRestServiceServer(ProviderMockConfig) 픽스처, sync_runs 는 매 테스트 비운다.
 */
public abstract class SyncIntegrationTest extends EventIntegrationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Autowired
    protected MockRestServiceServer providers;
    @Autowired
    protected SyncRunRepository syncRuns;
    @Autowired
    protected EventSourceRepository sourceRepository;
    @Autowired
    protected EventDiscoveryRepository discoveryRepository;
    @Autowired
    protected EventMatchCandidateRepository candidateRepository;
    @Autowired
    protected EventSourceChangeRepository changeRepository;
    @Autowired
    protected VenueRepository venueRepository;

    @BeforeEach
    void resetProviders() {
        providers.reset();
        syncRuns.deleteAll();
    }

    protected static DefaultResponseCreator fixture(String path, MediaType type) {
        return MockRestResponseCreators.withSuccess(new ClassPathResource("fixtures/" + path), type);
    }

    protected Event reload(Long id) {
        return eventRepository.findById(id).orElseThrow();
    }

    // 트랜잭션 밖의 지연 프록시 대신 공연장을 다시 읽는다
    protected Venue venueOf(Long eventId) {
        Long venueId = reload(eventId).venueId();
        return venueId == null ? null : venueRepository.findById(venueId).orElseThrow();
    }

    protected List<SyncRun> runs() {
        return syncRuns.findAll();
    }
}
