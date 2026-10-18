package com.janspace.integration.ticketmaster;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ticketmaster Discovery API 설정 (janspace.ticketmaster). keyword 는 아티스트 검색어 (JANNABI).
 */
@ConfigurationProperties(prefix = "janspace.ticketmaster")
public record TicketmasterProperties(String apiKey, String baseUri, String keyword) {
}
