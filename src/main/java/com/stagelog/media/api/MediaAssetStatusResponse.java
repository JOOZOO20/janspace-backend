package com.stagelog.media.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.media.application.MediaAssetQuery.AssetView;
import com.stagelog.media.application.MediaAssetQuery.RenditionView;
import com.stagelog.ops.application.QueueFailureQuery.Failure;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 진단용 미디어 asset 상태 (rendition 과 Queue 실패 기록 포함).
 */
@Schema(description = "미디어 asset 상태")
public record MediaAssetStatusResponse(
        @Schema(description = "asset ID", example = "41", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "asset 상태 (rendition 집계)", example = "PARTIAL", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"PENDING", "READY", "PARTIAL", "FAILED"})
        String status,
        @Schema(description = "원본 S3 key", example = "posters/12/3f2a9c1e.jpg", requiredMode = Schema.RequiredMode.REQUIRED)
        String originalKey,
        @Schema(description = "원본 SHA-256 hex", example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String originalSha256,
        @Schema(description = "원본 너비 (EXIF 회전 후, 입구 검증 실패면 0)", example = "1600", requiredMode = Schema.RequiredMode.REQUIRED)
        int width,
        @Schema(description = "원본 높이", example = "2000", requiredMode = Schema.RequiredMode.REQUIRED)
        int height,
        @Schema(description = "원본 MIME type", example = "image/jpeg", requiredMode = Schema.RequiredMode.REQUIRED)
        String mimeType,
        @Schema(description = "등록 시각 (UTC)", example = "2026-11-05T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt,
        @Schema(description = "마지막 변경 시각 (UTC)", example = "2026-11-05T03:00:30Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant updatedAt,
        @Schema(description = "소유자의 현재 원본과 같은 asset 인지 (서빙은 current asset 의 rendition 만 쓴다)", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean current,
        @Schema(description = "rendition (3 profile × 2 format)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<RenditionResponse> renditions) {

    @Schema(description = "rendition 하나")
    public record RenditionResponse(
            @Schema(description = "rendition ID", example = "245", requiredMode = Schema.RequiredMode.REQUIRED)
            Long id,
            @Schema(description = "화면 profile", example = "list", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"thumb", "list", "detail"})
            String profile,
            @Schema(description = "출력 형식", example = "webp", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"webp", "jpeg", "png"})
            String format,
            @Schema(description = "상태", example = "FAILED", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"PENDING", "PROCESSING", "READY", "FAILED"})
            String status,
            @Schema(description = "claim 횟수", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
            int attempt,
            @Schema(description = "처리 중인 Worker (PROCESSING 일 때만)", example = "worker-1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String leaseOwner,
            @Schema(description = "Lease 만료 시각 (UTC)", example = "2026-11-05T03:01:30Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant leaseExpiresAt,
            @Schema(description = "마지막 오류", example = "FFMPEG_FAILED: exit=1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String lastError,
            @Schema(description = "출력 S3 key (READY 일 때)", example = "media/41/list-1a2b3c4d.webp", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String outputKey,
            @Schema(description = "출력 크기 (byte)", example = "48213", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Long bytes,
            @Schema(description = "이 rendition 변환 메시지의 Queue 실패 기록", requiredMode = Schema.RequiredMode.REQUIRED)
            List<QueueFailureResponse> queueFailures) {

        static RenditionResponse from(RenditionView r) {
            return new RenditionResponse(r.id(), r.profile(), r.format(), r.status(), r.attempt(), r.leaseOwner(), r.leaseExpiresAt(),
                    r.lastError(), r.outputKey(), r.bytes(), r.failures().stream().map(QueueFailureResponse::from).toList());
        }
    }

    @Schema(description = "Queue 메시지 실패 기록 (queue_message_failures)")
    public record QueueFailureResponse(
            @Schema(description = "메시지 ID", example = "6f1c0d1e-8a4b-4f0e-9b7a-2c3d4e5f6a7b", requiredMode = Schema.RequiredMode.REQUIRED)
            String messageId,
            @Schema(description = "Queue", example = "jan-media-transcode", requiredMode = Schema.RequiredMode.REQUIRED)
            String queue,
            @Schema(description = "처리 실패 횟수", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
            int attempts,
            @Schema(description = "마지막 오류", example = "TranscodeException: FFMPEG_FAILED", requiredMode = Schema.RequiredMode.REQUIRED)
            String lastError,
            @Schema(description = "영구 실패(재시도 없이 삭제)인지", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean permanent,
            @Schema(description = "마지막 실패 시각 (UTC)", example = "2026-11-05T03:02:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant lastFailedAt) {

        static QueueFailureResponse from(Failure f) {
            return new QueueFailureResponse(f.messageId(), f.queue(), f.attempts(), f.lastError(), f.permanent(), f.lastFailedAt());
        }
    }

    static MediaAssetStatusResponse from(AssetView a) {
        return new MediaAssetStatusResponse(a.id(), a.status(), a.originalKey(), a.originalSha256(), a.width(), a.height(), a.mimeType(),
                a.createdAt(), a.updatedAt(), a.current(), a.renditions().stream().map(RenditionResponse::from).toList());
    }
}
