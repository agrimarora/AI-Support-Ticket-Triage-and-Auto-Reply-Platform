package com.ticket.web;

import com.ticket.core.security.ApiKeyHasher;
import com.ticket.domain.tenant.ApiKey;
import com.ticket.domain.tenant.ApiKeyRepository;
import com.ticket.domain.tenant.Tenant;
import com.ticket.domain.tenant.TenantRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/tenants")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateTenantRequest {
        private String name;
        private String slug;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateTenantResponse {
        private UUID tenantId;
        private String name;
        private String slug;
        private String rawApiKey;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<CreateTenantResponse> createTenant(@RequestBody CreateTenantRequest request) {
        Tenant tenant = Tenant.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .status("ACTIVE")
                .build();

        Tenant savedTenant = tenantRepository.save(tenant);

        String rawApiKey = "tk_" + UUID.randomUUID().toString().replace("-", "");
        String hashedKey = ApiKeyHasher.hashApiKey(rawApiKey);

        ApiKey apiKey = ApiKey.builder()
                .tenantId(savedTenant.getId())
                .name("Default Key for " + savedTenant.getName())
                .keyHash(hashedKey)
                .revoked(false)
                .build();

        apiKeyRepository.save(apiKey);

        CreateTenantResponse response = CreateTenantResponse.builder()
                .tenantId(savedTenant.getId())
                .name(savedTenant.getName())
                .slug(savedTenant.getSlug())
                .rawApiKey(rawApiKey)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
