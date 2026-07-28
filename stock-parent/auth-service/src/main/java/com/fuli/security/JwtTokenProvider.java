package com.fuli.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private final Key key;
    private final long expireMs;

    public JwtTokenProvider(@Value("${security.jwt.secret}") String secret,
                            @Value("${security.jwt.expire-ms:86400000}") long expireMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMs = expireMs;
    }

    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMs);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public boolean validateToken(String token) {
        Jwts.parser().verifyWith((javax.crypto.SecretKey) key).build().parseSignedClaims(token);
        return true;
    }

    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith((javax.crypto.SecretKey) key).build().parseSignedClaims(token).getPayload();
    }

    public long getExpireAt(String token) {
        return parseClaims(token).getExpiration().getTime();
    }
}
