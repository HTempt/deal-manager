package com.dealmanager.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationDays;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-days}") long expirationDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationDays = expirationDays;
    }

    public String create(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder().subject(userId.toString()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofDays(expirationDays))))
                .signWith(key).compact();
    }

    public Long parse(String token) {
        return Long.valueOf(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject());
    }
}
