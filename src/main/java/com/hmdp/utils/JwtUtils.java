package com.hmdp.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT工具类
 */
public class JwtUtils {

    private static final String SECRET = "hmdp-jwt-secret-key-2026";

    private static final long EXPIRE_TIME = 30 * 60 * 1000; // 默认过期时间30分钟

    /**
     * 创建JWT Token
     * @param userId 用户ID
     * @return JWT Token字符串
     */
    public static String createToken(Long userId) {
        return createToken(userId, EXPIRE_TIME);
    }

    /**
     * 创建JWT Token
     * @param userId 用户ID
     * @param expireTime 过期时间（毫秒）
     * @return JWT Token字符串
     */
    public static String createToken(Long userId, long expireTime) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expireTime);

        return Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setHeaderParam("alg", "HS256")
                .claim("userId", userId)
                .setIssuedAt(now)
                .setExpiration(expireDate)
                .signWith(SignatureAlgorithm.HS256, SECRET.getBytes(StandardCharsets.UTF_8))
                .compact();
    }

    /**
     * 创建JWT Token（携带额外信息）
     * @param userId 用户ID
     * @param payloadMap 额外的载荷信息
     * @return JWT Token字符串
     */
    public static String createToken(Long userId, Map<String, Object> payloadMap) {
        return createToken(userId, payloadMap, EXPIRE_TIME);
    }

    /**
     * 创建JWT Token（携带额外信息）
     * @param userId 用户ID
     * @param payloadMap 额外的载荷信息
     * @param expireTime 过期时间（毫秒）
     * @return JWT Token字符串
     */
    public static String createToken(Long userId, Map<String, Object> payloadMap, long expireTime) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expireTime);

        JwtBuilder jwtBuilder = Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setHeaderParam("alg", "HS256")
                .claim("userId", userId)
                .setIssuedAt(now)
                .setExpiration(expireDate);

        if (payloadMap != null && !payloadMap.isEmpty()) {
            payloadMap.forEach(jwtBuilder::claim);
        }

        return jwtBuilder
                .signWith(SignatureAlgorithm.HS256, SECRET.getBytes(StandardCharsets.UTF_8))
                .compact();
    }

    /**
     * 验证并解析JWT Token
     * @param token JWT Token字符串
     * @return Claims对象，验证失败返回null
     */
    public static Claims verify(String token) {
        try {
            return Jwts.parser()
                    .setSigningKey(SECRET.getBytes(StandardCharsets.UTF_8))
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从Token中获取用户ID
     * @param token JWT Token字符串
     * @return 用户ID，解析失败返回null
     */
    public static Long getUserIdFromToken(String token) {
        Claims claims = verify(token);
        if (claims != null) {
            return claims.get("userId", Long.class);
        }
        return null;
    }

    /**
     * 检查Token是否过期
     * @param token JWT Token字符串
     * @return true-已过期或无效，false-未过期
     */
    public static boolean isExpired(String token) {
        Claims claims = verify(token);
        if (claims == null) {
            return true;
        }
        Date expiration = claims.getExpiration();
        return expiration == null || expiration.before(new Date());
    }

    /**
     * 刷新Token（重新生成一个相同用户ID的新Token）
     * @param token 旧的JWT Token字符串
     * @return 新的JWT Token，如果旧Token无效则返回null
     */
    public static String refreshToken(String token) {
        Long userId = getUserIdFromToken(token);
        if (userId != null) {
            return createToken(userId);
        }
        return null;
    }
}
