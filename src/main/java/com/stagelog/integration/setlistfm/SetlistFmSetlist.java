package com.stagelog.integration.setlistfm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * setlist.fm 셋리 응답 (GET /rest/1.0/setlist/{id}). eventDate 는 "dd-MM-yyyy". 내부 모델 변환은 사용하는 기능의 infrastructure 가 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SetlistFmSetlist(String id, String versionId, String eventDate, String url, Venue venue, Sets sets) {

    static final DateTimeFormatter EVENT_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Venue(String id, String name, City city) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record City(String name, String stateCode, Country country, Coords coords) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Country(String code, String name) {
    }

    // "long" 은 자바 예약어라 lng 로 받는다
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Coords(Double lat, @JsonProperty("long") Double lng) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sets(List<Set> set) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Set(String name, Integer encore, List<Song> song) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Song(String name, Cover cover, Boolean tape, String info) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cover(String mbid, String name) {
    }

    // 세트 순서대로 편 곡 객체 (커버 정보 포함)
    public List<Song> songs() {
        if (sets == null || sets.set() == null) {
            return List.of();
        }
        return sets.set().stream().filter(s -> s.song() != null).flatMap(s -> s.song().stream()).toList();
    }

    public LocalDate eventLocalDate() {
        return eventDate == null ? null : LocalDate.parse(eventDate, EVENT_DATE);
    }

    // 세트(본편·앙코르) 순서대로 편 곡명. 빈 이름은 제외
    public List<String> songNames() {
        if (sets == null || sets.set() == null) {
            return List.of();
        }
        return sets.set().stream()
                .filter(s -> s.song() != null)
                .flatMap(s -> s.song().stream())
                .map(Song::name)
                .filter(n -> n != null && !n.isBlank())
                .toList();
    }
}
