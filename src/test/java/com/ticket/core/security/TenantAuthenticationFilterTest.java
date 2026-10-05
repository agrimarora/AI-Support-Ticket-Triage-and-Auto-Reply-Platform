package com.ticket.core.security;

import com.ticket.core.tenant.TenantContext;
import com.ticket.domain.tenant.ApiKey;
import com.ticket.domain.tenant.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TenantAuthenticationFilterTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private TenantAuthenticationFilter filter;

    @Test
    @DisplayName("Filter Authentication: Valid X-API-Key sets TenantContext and invokes filterChain")
    void testDoFilter_Success() throws Exception {
        String rawKey = "tk_valid_key_12345";
        String hashedKey = ApiKeyHasher.hashApiKey(rawKey);
        UUID tenantId = UUID.randomUUID();

        when(request.getHeader("X-API-Key")).thenReturn(rawKey);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("apikey:" + hashedKey)).thenReturn(tenantId.toString());

        doAnswer(invocation -> {
            assertThat(TenantContext.getTenantId()).isEqualTo(tenantId);
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(TenantContext.getTenantId()).isNull(); // Cleared in finally block
    }

    @Test
    @DisplayName("Filter Authentication: Missing X-API-Key returns 401 Unauthorized")
    void testDoFilter_MissingHeader() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn(null);

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(stringWriter.toString()).contains("Missing X-API-Key header");
    }
}
