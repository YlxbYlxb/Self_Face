package com.selfface.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

@Slf4j
@Component
public class JwtUtil {

    /**
     * 与 application.yml 中的内置默认值保持一致。
     * 这个值已经写进开源仓库，任何人都能伪造 token，生产环境必须覆盖。
     */
    static final String DEV_SECRET = "selfface-dev-secret-please-change-it-2026";

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expire-hours:168}")
    private long expireHours;

    private final Environment environment;

    private SecretKey key;

    public JwtUtil(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void init() {
        if (DEV_SECRET.equals(secret) && isProd()) {
            throw new IllegalStateException(
                    "生产环境不能使用内置的开发用 JWT 密钥（该值已公开，任何人都能伪造登录态）。"
                            + "请设置环境变量 JWT_SECRET，生成示例：openssl rand -base64 48");
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException("app.jwt.secret 至少需要 32 个字节，当前 " + raw.length);
        }
        if (DEV_SECRET.equals(secret)) {
            log.warn("正在使用内置开发 JWT 密钥 —— 仅限本地使用，部署前请设置 JWT_SECRET");
        }
        this.key = Keys.hmacShaKeyFor(raw);
    }

    public String generate(Long userId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireHours * 3600_000L))
                .signWith(key)
                .compact();
    }

    /**
     * @return token 里的 userId，无效或过期返回 null
     */
    public Long parseUserId(String token) {
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return Long.valueOf(jws.getPayload().getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long getExpireSeconds() {
        return expireHours * 3600;
    }

    private boolean isProd() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}
