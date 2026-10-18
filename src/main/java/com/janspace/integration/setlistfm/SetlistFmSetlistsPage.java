package com.janspace.integration.setlistfm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * setlist.fm 아티스트 셋리 목록 한 페이지 (GET /rest/1.0/artist/{mbid}/setlists?p=). 페이지는 1 부터.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SetlistFmSetlistsPage(int total, int page, int itemsPerPage, List<SetlistFmSetlist> setlist) {

    public List<SetlistFmSetlist> items() {
        return setlists();
    }

    public List<SetlistFmSetlist> setlists() {
        return setlist == null ? List.of() : setlist;
    }
}
