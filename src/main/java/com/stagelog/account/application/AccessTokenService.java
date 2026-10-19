package com.stagelog.account.application;

import com.stagelog.common.auth.AuthenticatedUser;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 액세스 JWT 발급·검증 (Nimbus HS256). sub = userId, adm = 관리자 여부.
 */
@Slf4j
@Component
public class AccessTokenService implements AccessTokenVerifier {

    private static final String ADMIN_CLAIM = "adm";

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public AccessTokenService(@Value("${stagelog.jwt.secret}") String secret,
                              @Value("${stagelog.jwt.access-ttl}") Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    AccessTokenService(String secret, Duration ttl, Clock clock) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        // HS256 은 키가 32바이트 이상이어야 안전하다. 짧으면 기동 자체를 막는다
        if (bytes.length < 32) {
            throw new IllegalStateException("stagelog.jwt.secret 은 32바이트 이상이어야 합니다");
        }
        this.secret = bytes;
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(Long userId, boolean admin) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .claim(ADMIN_CLAIM, admin)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("액세스 토큰 서명에 실패했습니다", e);
        }
        return jwt.serialize();
    }

    @Override
    public Optional<AuthenticatedUser> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secret))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || !exp.toInstant().isAfter(clock.instant())) {
                return Optional.empty();
            }
            Boolean admin = claims.getBooleanClaim(ADMIN_CLAIM);
            return Optional.of(new AuthenticatedUser(Long.parseLong(claims.getSubject()), Boolean.TRUE.equals(admin)));
        } catch (ParseException | JOSEException | NumberFormatException e) {
            // 위조·손상 토큰은 흔하므로 스택 없이 한 줄만 남긴다
            log.warn("[액세스 토큰 검증 실패] reason={}", e.getMessage());
            return Optional.empty();
        }
    }
}
