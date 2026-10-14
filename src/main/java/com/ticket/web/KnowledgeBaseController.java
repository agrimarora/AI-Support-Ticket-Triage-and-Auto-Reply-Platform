package com.ticket.web;

import com.ticket.domain.budget.BudgetRateLimitService;
import com.ticket.domain.budget.TenantBudget;
import com.ticket.domain.budget.TokenUsageLog;
import com.ticket.domain.budget.TokenUsageLogRepository;
import com.ticket.domain.kb.KnowledgeBaseService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final BudgetRateLimitService budgetRateLimitService;
    private final TokenUsageLogRepository tokenUsageLogRepository;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IngestDocumentRequest {
        private String title;
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateBudgetRequest {
        private UUID tenantId;
        private Integer dailyTokenLimit;
        private Integer rateLimitPerMinute;
    }

    @PostMapping("/documents")
    public ResponseEntity<KnowledgeBaseService.IngestionResult> ingestDocument(@RequestBody IngestDocumentRequest request) {
        KnowledgeBaseService.IngestionResult result =
                knowledgeBaseService.ingestDocument(request.getTitle(), request.getContent());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/budget")
    public ResponseEntity<TenantBudget> getBudget(@RequestParam(value = "tenantId", required = false) UUID tenantId) {
        UUID effectiveId = tenantId != null ? tenantId : com.ticket.core.tenant.TenantContext.getTenantId();
        return ResponseEntity.ok(budgetRateLimitService.getOrCreateBudget(effectiveId));
    }

    @PostMapping("/budget")
    public ResponseEntity<TenantBudget> updateBudget(@RequestBody UpdateBudgetRequest request) {
        UUID effectiveId = request.getTenantId() != null ? request.getTenantId() : com.ticket.core.tenant.TenantContext.getTenantId();
        return ResponseEntity.ok(budgetRateLimitService.updateBudget(effectiveId, request.getDailyTokenLimit(), request.getRateLimitPerMinute()));
    }

    @GetMapping("/usage")
    public ResponseEntity<List<TokenUsageLog>> getUsage(@RequestParam(value = "tenantId", required = false) UUID tenantId) {
        UUID effectiveId = tenantId != null ? tenantId : com.ticket.core.tenant.TenantContext.getTenantId();
        return ResponseEntity.ok(tokenUsageLogRepository.findByTenantIdOrderByCreatedAtDesc(effectiveId));
    }
}
