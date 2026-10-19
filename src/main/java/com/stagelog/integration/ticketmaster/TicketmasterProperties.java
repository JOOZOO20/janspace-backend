package com.stagelog.integration.ticketmaster;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ticketmaster Discovery API 설정 (stagelog.ticketmaster). 키와 keyword(아티스트 검색어)는 환경변수로만 들어온다.
 */
@ConfigurationProperties(prefix = "stagelog.ticketmaster")
public record TicketmasterProperties(String apiKey, String baseUri, String keyword) {
}
