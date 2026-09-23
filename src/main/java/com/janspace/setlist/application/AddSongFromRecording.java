package com.janspace.setlist.application;

import com.janspace.setlist.domain.RevisionSource;
import com.janspace.setlist.domain.SetlistPage;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기록 흐름에서 고른 곡이 위키에 없으면 맨 뒤에 붙인다.
 * attendance 의 최고의 곡 선택(BestSongChosen)이 이 메서드를 부르거나 어댑터가 이벤트를 잇는다.
 */
@Service
@RequiredArgsConstructor
public class AddSongFromRecording {

    private final SetlistWriter writer;

    // 이미 있으면 아무것도 하지 않는다. 추가했으면 true
    @Transactional
    public boolean addIfMissing(Long eventId, Long songId) {
        SetlistPage page = writer.pageOf(eventId);
        List<SetlistEntry> current = writer.currentEntries(eventId);
        if (current.stream().anyMatch(e -> e.songId().equals(songId))) {
            return false;
        }
        List<SetlistEntry> entries = new ArrayList<>(current);
        entries.add(new SetlistEntry(songId, current.size() + 1, false));
        writer.write(page, entries, RevisionSource.FAN, null);
        return true;
    }
}
