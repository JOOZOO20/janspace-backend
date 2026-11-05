package com.stagelog.setlist.application.port;

import com.stagelog.setlist.application.QuotaScope;
import java.util.List;
import java.util.Optional;

/**
 * 유튜브 재생목록 포트. 채널 미연결이면 409 YOUTUBE_NOT_CONNECTED, API 실패면 502 YOUTUBE_API_ERROR,
 * Quota 예약 실패나 API 의 quotaExceeded 면 429 YOUTUBE_QUOTA_EXHAUSTED 를 던진다. 모든 호출은 scope 로 Quota 원장에 남는다.
 */
public interface PlaylistPort {

    enum Outcome {
        // 적용 뒤 다시 읽은 항목이 목표(건너뛴 영상 제외)와 같다
        OK,
        // 적용했지만 1·2·4·8초 재조회 뒤에도 목표와 다르다
        PARTIAL,
        // 계산한 연산을 로컬에서 적용한 결과가 목표와 달라 아무것도 호출하지 않았다
        DIFF_INVALID
    }

    // skipped = 유튜브에 없어 건너뛴 영상 ID(순서대로), writes = 보낸 항목 쓰기 호출 수, detail = PARTIAL·DIFF_INVALID 사유
    record ItemSync(Outcome outcome, List<String> skipped, int writes, String detail) {

        public static ItemSync ok(List<String> skipped, int writes) {
            return new ItemSync(Outcome.OK, skipped, writes, null);
        }
    }

    // 만들어진 재생목록 ID
    String create(String title, String description, QuotaScope scope);

    // 재생목록 항목을 주어진 영상 순서와 같게 맞춘다. 유튜브에 없는 영상(삭제·비공개)은 건너뛰고 결과에 순서대로 담는다
    ItemSync syncItems(String playlistId, List<String> videoIds, QuotaScope scope);

    // 재생목록이 유튜브에서 지워졌으면 empty
    Optional<String> currentTitle(String playlistId, QuotaScope scope);
}
