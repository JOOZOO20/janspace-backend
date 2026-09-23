package com.janspace.event.application.sync;

import com.janspace.ops.application.OperatorAlert;
import com.janspace.ops.application.SyncRunRecorder;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 동기화 실행 틀: sync_runs 시작 -> 본문 -> OK/FAILED 종료. 본문 예외는 밖으로 내지 않고 FAILED 기록 + 운영자 알림으로 바꾼다
 * (스케줄러·관리자 트리거가 같은 틀을 탄다). 요약의 URL 에 섞인 키 값은 가린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRunner {

    private static final java.util.regex.Pattern SECRET_PARAM =
            java.util.regex.Pattern.compile("(?i)(apikey|service|publishedKey|x-api-key)=[^&\\s\"']+");

    private final SyncRunRecorder recorder;
    private final OperatorAlert operatorAlert;

    /** 실행 결과. source 는 sync_runs.source 이름(제공자 이름), summary 는 사람이 읽는 한 줄 요약. */
    public record SyncResult(String source, boolean ok, String summary) {
        public boolean failed() {
            return !ok;
        }

        public static SyncResult skipped(String source, String reason) {
            return new SyncResult(source, true, reason);
        }
    }

    public SyncResult run(String source, Supplier<String> body) {
        Long runId = recorder.start(source);
        try {
            String summary = body.get();
            recorder.finish(runId, true, summary);
            return new SyncResult(source, true, summary);
        } catch (RuntimeException e) {
            String summary = "실패: " + mask(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("[동기화 실패] source={}, runId={}", source, runId, e);
            recorder.finish(runId, false, summary);
            operatorAlert.send("[동기화 실패] source=" + source + " " + summary);
            return new SyncResult(source, false, summary);
        }
    }

    static String mask(String text) {
        return text == null ? "" : SECRET_PARAM.matcher(text).replaceAll("$1=***");
    }
}
