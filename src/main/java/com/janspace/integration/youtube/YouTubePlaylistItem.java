package com.janspace.integration.youtube;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * playlistItems 리소스 중 쓰는 부분. id 는 삭제에, snippet.resourceId.videoId 는 대조에 쓴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubePlaylistItem(String id, Snippet snippet) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snippet(String playlistId, String title, Integer position, ResourceId resourceId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceId(String kind, String videoId) {
    }

    public String videoId() {
        return snippet == null || snippet.resourceId() == null ? null : snippet.resourceId().videoId();
    }
}
