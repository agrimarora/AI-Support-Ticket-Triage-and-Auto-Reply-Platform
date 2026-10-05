package com.ticket.domain.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.exception.TicketNotFoundException;
import com.ticket.core.tenant.TenantContext;
import com.ticket.domain.outbox.OutboxEvent;
import com.ticket.domain.outbox.OutboxEventRepository;
import com.ticket.dto.CreateTicketRequest;
import com.ticket.dto.TicketResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TicketService ticketService;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Create Ticket: Saves ticket and creates transactional outbox event atomically")
    void testCreateTicket_Success() {
        CreateTicketRequest request = CreateTicketRequest.builder()
                .customerId("cust-99")
                .title("Cannot login to portal")
                .description("Invalid credentials error when attempting login")
                .build();

        UUID ticketId = UUID.randomUUID();
        Ticket savedTicket = Ticket.builder()
                .id(ticketId)
                .tenantId(tenantId)
                .customerId("cust-99")
                .title("Cannot login to portal")
                .description("Invalid credentials error when attempting login")
                .status(TicketStatus.RECEIVED)
                .idempotencyKey("key-123")
                .build();

        when(ticketRepository.findByTenantIdAndIdempotencyKey(tenantId, "key-123")).thenReturn(Optional.empty());
        when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);

        TicketResponse response = ticketService.createTicket(request, "key-123");

        assertThat(response.getId()).isEqualTo(ticketId);
        assertThat(response.getTenantId()).isEqualTo(tenantId);
        assertThat(response.getStatus()).isEqualTo(TicketStatus.RECEIVED);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent outboxEvent = outboxCaptor.getValue();
        assertThat(outboxEvent.getTenantId()).isEqualTo(tenantId);
        assertThat(outboxEvent.getAggregateType()).isEqualTo("TICKET");
        assertThat(outboxEvent.getEventType()).isEqualTo("TICKET_CREATED");
        assertThat(outboxEvent.getPayload()).contains("Cannot login to portal");
    }

    @Test
    @DisplayName("Idempotency Check: Existing ticket with same Idempotency-Key returns duplicate response without re-saving")
    void testCreateTicket_IdempotentDuplicate() {
        CreateTicketRequest request = CreateTicketRequest.builder()
                .customerId("cust-99")
                .title("New title ignored")
                .description("New description ignored")
                .build();

        UUID ticketId = UUID.randomUUID();
        Ticket existingTicket = Ticket.builder()
                .id(ticketId)
                .tenantId(tenantId)
                .customerId("cust-99")
                .title("Original title")
                .description("Original description")
                .status(TicketStatus.RECEIVED)
                .idempotencyKey("idem-dup-key")
                .build();

        when(ticketRepository.findByTenantIdAndIdempotencyKey(tenantId, "idem-dup-key"))
                .thenReturn(Optional.of(existingTicket));

        TicketResponse response = ticketService.createTicket(request, "idem-dup-key");

        assertThat(response.getId()).isEqualTo(ticketId);
        assertThat(response.getTitle()).isEqualTo("Original title");

        verify(ticketRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Tenant Isolation: getTicketById throws TicketNotFoundException when ticket belongs to another tenant")
    void testGetTicketById_TenantMismatch() {
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findByIdAndTenantId(ticketId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.getTicketById(ticketId))
                .isInstanceOf(TicketNotFoundException.class)
                .hasMessageContaining("Ticket not found");
    }
}
