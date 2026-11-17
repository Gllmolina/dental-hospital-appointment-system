package com.dental.hospital;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Component
public class JwtTokens {
    private static final String ISSUER = "dental-hospital";
    private static final String AUDIENCE = "hospital-web";
    private final byte[] secret;

    public JwtTokens(@Value("${JWT_SECRET:}") String value) {
        secret = value.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 UTF-8 bytes");
    }

    public String issue(long userId, String jti, Instant now, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(AUDIENCE).subject(Long.toString(userId))
                    .jwtID(jti).issueTime(Date.from(now)).expirationTime(Date.from(expiresAt)).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
    }

    public Claims verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret)))
                throw new IllegalArgumentException("JWT signature invalid");
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now();
            if (!ISSUER.equals(claims.getIssuer()) || !List.of(AUDIENCE).equals(claims.getAudience())
                    || claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now)
                    || claims.getIssueTime() == null || claims.getIssueTime().toInstant().isAfter(now.plusSeconds(30))
                    || claims.getJWTID() == null || claims.getJWTID().isBlank())
                throw new IllegalArgumentException("JWT claims invalid");
            return new Claims(Long.parseLong(claims.getSubject()), claims.getJWTID());
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT invalid", e);
        }
    }

    public record Claims(long userId, String jti) {
    }
}
