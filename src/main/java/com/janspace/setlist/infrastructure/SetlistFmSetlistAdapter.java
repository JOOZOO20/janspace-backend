package com.janspace.setlist.infrastructure;

import com.janspace.integration.setlistfm.SetlistFmClient;
import com.janspace.integration.setlistfm.SetlistFmSetlist;
import com.janspace.setlist.application.port.FmSetlist;
import com.janspace.setlist.application.port.SetlistFmSetlistPort;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * setlist.fm 응답 -> FmSetlist. 404 는 empty, 그 외 오류는 그대로 던져 병합 실행이 FAILED 로 남게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetlistFmSetlistAdapter implements SetlistFmSetlistPort {

    private final SetlistFmClient client;

    @Override
    public Optional<FmSetlist> fetch(String setlistId) {
        try {
            return Optional.ofNullable(client.getSetlist(setlistId)).map(SetlistFmSetlistAdapter::toFmSetlist);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("[setlist.fm 셋리 없음] id={}", setlistId);
            return Optional.empty();
        }
    }

    static FmSetlist toFmSetlist(SetlistFmSetlist s) {
        return new FmSetlist(s.id(), s.versionId(), s.eventLocalDate(), s.songNames());
    }
}
