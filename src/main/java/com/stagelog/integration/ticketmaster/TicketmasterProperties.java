package com.stagelog.integration.ticketmaster;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ticketmaster Discovery API 설정 (stagelog.ticketmaster). keyword 는 아티스트 검색어 (JANNABI).
 */
@ConfigurationProperties(prefix = "stagelog.ticketmaster")
public record TicketmasterProperties(String apiKey, String baseUri, String keyword) {
}
