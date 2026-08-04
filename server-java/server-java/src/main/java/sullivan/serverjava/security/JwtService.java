package sullivan.serverjava.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class JwtService {
    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-ttl-seconds}") long ttlSeconds) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(principal.id())
                .claim("principal_type", principal.principalType())
                .claim("roles", principal.roles())
                .claim("permissions", principal.permissions())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public AuthPrincipal parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthPrincipal(
                claims.getSubject(),
                claims.get("principal_type", String.class),
                stringSet(claims.get("roles", List.class)),
                stringSet(claims.get("permissions", List.class)));
    }

    private Set<String> stringSet(List<?> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.forEach(value -> result.add(String.valueOf(value)));
        }
        return Set.copyOf(result);
    }
}
