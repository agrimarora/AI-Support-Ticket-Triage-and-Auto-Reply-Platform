package com.ticket.worker;

import com.ticket.domain.ticket.TicketPriority;
import com.ticket.dto.ClassificationResultDto;
import com.ticket.dto.TicketEventDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class ClassificationWorkerTest {

    private final ClassificationWorker classificationWorker = new ClassificationWorker();

    @Test
    @DisplayName("Classification: Classifies billing inquiries as BILLING category")
    void testClassifyBillingTicket() {
        TicketEventDto event = TicketEventDto.builder()
                .ticketId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .title("Double charge on monthly invoice")
                .description("I noticed an extra payment charge on my credit card statement")
                .build();

        ClassificationResultDto result = classificationWorker.classifyTicket(event);

        assertThat(result.getCategory()).isEqualTo("BILLING");
        assertThat(result.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(result.getConfidenceScore()).isGreaterThanOrEqualTo(0.90);
    }

    @Test
    @DisplayName("Classification: Classifies app crashes as TECHNICAL_BUG with high priority")
    void testClassifyBugTicket() {
        TicketEventDto event = TicketEventDto.builder()
                .ticketId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .title("Critical error: application crash on launch")
                .description("System freezes when clicking button")
                .build();

        ClassificationResultDto result = classificationWorker.classifyTicket(event);

        assertThat(result.getCategory()).isEqualTo("TECHNICAL_BUG");
        assertThat(result.getPriority()).isEqualTo(TicketPriority.URGENT);
        assertThat(result.getConfidenceScore()).isGreaterThanOrEqualTo(0.90);
    }
}
