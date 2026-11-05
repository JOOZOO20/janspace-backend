package com.stagelog.account.application;

import com.stagelog.account.domain.ServiceAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 계정 API key 검증 계약. bootstrap 의 ServiceAccountAuthenticationFilter 가 호출한다.
 * key 의 SHA-256 hex 로 찾고, 없거나 회수(disabled_at)된 계정은 예외가 아니라 empty 다.
 * key 는 충분히 긴 무작위 값이라 salt 없는 SHA-256 으로도 원문을 되돌릴 수 없다.
 */
@Service
@RequiredArgsConstructor
public class ServiceAccountAuthenticator {

    private final ServiceAccountRepository repository;

    /** 인증된 서비스 계정. authorities 는 Spring Security authority 이름 (MCP_READER, MCP_REQUESTER). */
    public record ServiceAccountIdentity(String name, Set<String> authorities) {
    }

    @Transactional(readOnly = true)
    public Optional<ServiceAccountIdentity> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByKeyHash(sha256Hex(apiKey.trim()))
                .filter(account -> account.isActive())
                .map(account -> new ServiceAccountIdentity(account.getName(),
                        account.getRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet())));
    }

    // MySQL SHA2(key, 256) 과 같은 소문자 hex
    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없는 JVM 입니다", e);
        }
    }
}
