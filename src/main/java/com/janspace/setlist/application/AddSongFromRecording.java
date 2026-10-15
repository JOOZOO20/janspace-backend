package com.janspace.setlist.application;

import com.janspace.setlist.domain.RevisionSource;
import com.janspace.setlist.domain.SetlistPage;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기록 흐름에서 고른 곡이 위키에 없으면 맨 뒤에 붙인다. attendance 의 BestSongChosen 을 setlist 리스너가 잇는다.
 * 기록한 사용자를 편집자로 남기고 팬 편집과 같은 하루 한도에 센다. 기록 PATCH 로 곡을 바꿔 가며 한도 없이 위키에 곡을 넣지 못하게 하기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddSongFromRecording {

    private final SetlistWriter writer;
    private final EditSetlistUseCase editUseCase;

    // 이미 있으면 아무것도 하지 않는다. 한도를 다 썼으면 붙이지 않는다 (기록의 최고의 곡은 그대로 남는다). 추가했으면 true
    @Transactional
    public boolean addIfMissing(Long eventId, Long songId, Long userId) {
        SetlistPage page = writer.pageOf(eventId);
        List<SetlistEntry> current = writer.currentEntries(eventId);
        if (current.stream().anyMatch(e -> e.songId().equals(songId))) {
            return false;
        }
        if (!editUseCase.hasQuota(userId)) {
            log.warn("[기록에서 곡 추가 건너뜀 - 편집 한도] eventId={}, songId={}, userId={}", eventId, songId, userId);
            return false;
        }
        List<SetlistEntry> entries = new ArrayList<>(current);
        entries.add(new SetlistEntry(songId, current.size() + 1, false));
        writer.write(page, entries, RevisionSource.FAN, userId);
        return true;
    }
}
