package com.ticket.worker;

import com.ticket.domain.ticket.TicketPriority;
import com.ticket.dto.ClassificationResultDto;
import com.ticket.dto.TicketEventDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ClassificationWorker {

    /**
     * Classifies incoming support ticket requests based on structured criteria.
     * Stubbed LLM classifier with schema validation for Phase 1.
     */
    public ClassificationResultDto classifyTicket(TicketEventDto event) {
        log.info("Classifying ticket id: {} for tenant: {}", event.getTicketId(), event.getTenantId());

        String text = (event.getTitle() + " " + event.getDescription()).toLowerCase();

        String category;
        TicketPriority priority;
        double confidence;
        String rationale;

        if (text.contains("billing") || text.contains("payment") || text.contains("invoice") || text.contains("charge")) {
            category = "BILLING";
            priority = text.contains("urgent") || text.contains("double") ? TicketPriority.HIGH : TicketPriority.MEDIUM;
            confidence = 0.92;
            rationale = "Keywords matched billing/payment domain";
        } else if (text.contains("bug") || text.contains("crash") || text.contains("error") || text.contains("fail")) {
            category = "TECHNICAL_BUG";
            priority = text.contains("critical") || text.contains("down") ? TicketPriority.URGENT : TicketPriority.HIGH;
            confidence = 0.95;
            rationale = "Keywords matched software error/crash behavior";
        } else if (text.contains("account") || text.contains("password") || text.contains("login")) {
            category = "ACCOUNT_ACCESS";
            priority = TicketPriority.MEDIUM;
            confidence = 0.88;
            rationale = "Keywords matched account credential management";
        } else {
            category = "GENERAL_INQUIRY";
            priority = TicketPriority.LOW;
            confidence = 0.75;
            rationale = "Default fallback classification";
        }

        ClassificationResultDto result = ClassificationResultDto.builder()
                .category(category)
                .priority(priority)
                .confidenceScore(confidence)
                .rationale(rationale)
                .build();

        validateSchema(result);

        return result;
    }

    private void validateSchema(ClassificationResultDto result) {
        if (result.getCategory() == null || result.getCategory().isBlank()) {
            throw new IllegalArgumentException("Classification result missing category");
        }
        if (result.getPriority() == null) {
            throw new IllegalArgumentException("Classification result missing priority");
        }
        if (result.getConfidenceScore() == null || result.getConfidenceScore() < 0.0 || result.getConfidenceScore() > 1.0) {
            throw new IllegalArgumentException("Classification result invalid confidence score");
        }
    }
}
