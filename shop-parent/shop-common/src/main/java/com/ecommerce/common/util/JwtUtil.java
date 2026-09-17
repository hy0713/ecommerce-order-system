package com.ecommerce.common.util;

import com.ecommerce.common.constant.AuthConstant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * JWT 工具：基于 HS256 签发与校验 token
 */
@Slf4j
@Component
public class JwtUtil {

    /** 角色声明键名 */
    private static final String CLAIM_ROLE = "role";

    /** HS256 要求的最小密钥长度（字节） */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 弱密钥特征：命中只告警不拦截（本地演示要能直接跑），生产必须覆盖。
     * 注意判定用 contains 而非 equals——占位值常被人改成 xxx-change-me-1 之类。
     */
    private static final String[] WEAK_SECRET_MARKERS = {"change-me", "please-change", "helpbydsv4"};

    /** 密钥由外部注入（环境变量 JWT_SECRET / 配置项 ecommerce.jwt.secret），**仓库不提供可用默认值** */
    @Value("${ecommerce.jwt.secret:}")
    private String secret;

    @Value("${ecommerce.jwt.expire-seconds}")
    private long expireSeconds;

    private Key key;

    /**
     * 初始化签名密钥，**缺失或过短直接拒绝启动（fail-fast）**。
     *
     * <p>为什么不做「默认密钥」兜底：HS256 是对称签名，密钥一旦公开，任何人
     * 都能自己签一个 token 冒充任意用户（包括 ADMIN），网关验签根本拦不住。
     * 而仓库里的默认值是**公开**的——"部署时忘了配环境变量"就成了静默的越权入口。
     * 宁可起不来，也不要带着公开密钥对外服务。
     */
    @PostConstruct
    public void init() {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "JWT 密钥未配置：请设置环境变量 JWT_SECRET（>= " + MIN_SECRET_BYTES + " 字节），"
                            + "或配置项 ecommerce.jwt.secret");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足：HS256 要求至少 " + MIN_SECRET_BYTES + " 字节，当前 "
                            + secretBytes.length + " 字节");
        }
        String lower = secret.toLowerCase();
        for (String marker : WEAK_SECRET_MARKERS) {
            if (lower.contains(marker)) {
                log.warn("[安全告警] JWT 密钥看起来是开发/占位值，生产环境必须通过 JWT_SECRET 覆盖");
                break;
            }
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * 生成 token，subject 为 userId，附带角色声明
     */
    public String generateToken(Long userId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireSeconds * 1000L);
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role == null ? AuthConstant.ROLE_USER : role)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 校验 token 并解析 userId，校验失败返回 null
     */
    public Long parseUserId(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) {
            return null;
        }
        try {
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 token 中的角色声明；解析失败或未携带时按普通用户处理（最小权限）
     */
    public String parseRole(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) {
            return AuthConstant.ROLE_USER;
        }
        Object role = claims.get(CLAIM_ROLE);
        return role == null ? AuthConstant.ROLE_USER : String.valueOf(role);
    }

    private Claims parseClaims(String token) {
        try {
            Jws<Claims> claimsJws = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token);
            return claimsJws.getBody();
        } catch (Exception e) {
            return null;
        }
    }
}
