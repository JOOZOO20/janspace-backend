package com.stagelog.integration.ticketmaster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Discovery API 의 이벤트 한 건. 이미 회차 단위 ID 이며 dates.start 가 현지 날짜·시각, _embedded.venues[0] 이 공연장이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketmasterEvent(String id, String name, String url, Dates dates, List<Image> images,
                                @JsonProperty("_embedded") Embedded embedded, List<PriceRange> priceRanges) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dates(Start start, String timezone, Status status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Start(String localDate, String localTime, String dateTime, Boolean noSpecificTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(String url, String ratio, Integer width, Integer height) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Embedded(List<Venue> venues) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Venue(String id, String name, City city, Country country, Location location, String timezone) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record City(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Country(String countryCode, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(String latitude, String longitude) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceRange(String type, String currency, Double min, Double max) {
    }

    public Venue firstVenue() {
        return embedded == null || embedded.venues() == null || embedded.venues().isEmpty() ? null : embedded.venues().get(0);
    }
}
