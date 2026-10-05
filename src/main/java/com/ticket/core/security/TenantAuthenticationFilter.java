package com.ticket.core.security;

import com.ticket.core.tenant.TenantContext;
import com.ticket.domain.tenant.ApiKey;
import com.ticket.domain.tenant.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String REDIS_KEY_PREFIX = "apikey:";

    private final ApiKeyRepository apiKeyRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/v1/admin/") ||
               path.startsWith("/actuator") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String rawApiKey = request.getHeader(API_KEY_HEADER);

        if (rawApiKey == null || rawApiKey.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Unauthorized\", \"message\": \"Missing X-API-Key header\"}");
            return;
        }

        try {
            String hashedKey = ApiKeyHasher.hashApiKey(rawApiKey);
            UUID tenantId = getOrFetchTenantId(hashedKey);

            if (tenantId == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Unauthorized\", \"message\": \"Invalid or revoked API key\"}");
                return;
            }

            TenantContext.setTenantId(tenantId);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID getOrFetchTenantId(String hashedKey) {
        String redisKey = REDIS_KEY_PREFIX + hashedKey;
        try {
            String cachedTenantId = redisTemplate.opsForValue().get(redisKey);
            if (cachedTenantId != null) {
                return UUID.fromString(cachedTenantId);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable for API Key cache, falling back to database", e);
        }

        Optional<ApiKey> apiKeyOpt = apiKeyRepository.findByKeyHash(hashedKey);
        if (apiKeyOpt.isPresent() && apiKeyOpt.get().isValid()) {
            UUID tenantId = apiKeyOpt.get().getTenantId();
            try {
                redisTemplate.opsForValue().set(redisKey, tenantId.toString(), Duration.ofMinutes(15));
            } catch (Exception e) {
                log.warn("Failed to cache API key in Redis", e);
            }
            return tenantId;
        }

        return null;
    }
}
