package com.stagelog.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueuePropertiesTest {

    final QueueProperties props = new QueueProperties(new QueueProperties.Relay(true), new QueueProperties.Consumer(2, 20),
            Map.of("jan-source-apply", 60), List.of(30, 120, 600, 1800), false);

    @Test
    @DisplayName("수신 횟수 1~4 의 일시 실패는 30초·2분·10분·30분 뒤 재전달, 표를 넘으면 마지막 값")
    void backoff_follows_receive_count() {
        assertThat(List.of(1, 2, 3, 4, 7).stream().map(props::backoffAfter).toList()).containsExactly(30, 120, 600, 1800, 1800);
    }

    @Test
    @DisplayName("Visibility 설정이 없는 Queue 는 기동·처리 시 바로 드러난다")
    void unknown_queue_visibility_fails() {
        assertThat(props.visibilityOf("jan-source-apply")).isEqualTo(60);
        assertThatThrownBy(() -> props.visibilityOf("jan-media-transcode")).isInstanceOf(IllegalStateException.class);
        assertThat(QueueProperties.deadLetterQueue("jan-poster-ingest")).isEqualTo("jan-poster-ingest-dlq");
    }
}
