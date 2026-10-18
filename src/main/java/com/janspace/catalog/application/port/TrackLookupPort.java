package com.janspace.catalog.application.port;

import java.util.List;

/**
 * 곡·앨범 초안 후보 조회 (iTunes Search). 키 없이 호출한다.
 */
public interface TrackLookupPort {

    List<TrackCandidate> search(String term, String country);
}
