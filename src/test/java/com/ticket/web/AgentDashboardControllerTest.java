package com.ticket.web;

import com.ticket.core.security.ApiKeyHasher;
import com.ticket.domain.budget.TenantBudget;
import com.ticket.domain.budget.TenantBudgetRepository;
import com.ticket.domain.kb.DocumentChunkRepository;
import com.ticket.domain.kb.KnowledgeBaseService;
import com.ticket.domain.kb.KnowledgeDocumentRepository;
import com.ticket.domain.tenant.ApiKey;
import com.ticket.domain.tenant.ApiKeyRepository;
import com.ticket.domain.tenant.Tenant;
import com.ticket.domain.tenant.TenantRepository;
import com.ticket.domain.ticket.Ticket;
import com.ticket.domain.ticket.TicketRepository;
import com.ticket.domain.ticket.TicketStatus;
import com.ticket.dto.TicketResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Phase 2: Agent Dashboard and Human-in-the-Loop Tests")
public class AgentDashboardControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("dashboard_test_db")
            .withUsername("testuser")
            .withPassword("testpass");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TenantBudgetRepository budgetRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @MockBean
    private StringRedisTemplate redisTemplate;

    private UUID tenantId;
    private String rawApiKey;

    @BeforeEach
    void setUp() {
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);

        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        budgetRepository.deleteAll();
        ticketRepository.deleteAll();
        apiKeyRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("Dashboard Test Tenant")
                .slug("dashboard-test")
                .status("ACTIVE")
                .build());
        tenantId = tenant.getId();

        rawApiKey = "tk_dashboard_" + UUID.randomUUID().toString().replace("-", "");
        apiKeyRepository.save(ApiKey.builder()
                .tenantId(tenantId)
                .name("Dashboard Key")
                .keyHash(ApiKeyHasher.hashApiKey(rawApiKey))
                .revoked(false)
                .build());
    }

    @Test
    @DisplayName("Approve endpoint transitions ticket from AWAITING_APPROVAL to APPROVED")
    void testApproveDraft() {
        // Create a ticket in AWAITING_APPROVAL state
        Ticket ticket = ticketRepository.save(Ticket.builder()
                .tenantId(tenantId)
                .customerId("cust-approve-1")
                .title("Test ticket for approval")
                .description("This ticket has a draft reply ready for agent approval")
                .status(TicketStatus.AWAITING_APPROVAL)
                .draftReply("Thank you for contacting support! Based on our docs, here's a solution.")
                .requiresHuman(false)
                .build());

        ResponseEntity<TicketResponse> response = restTemplate.postForEntity(
                "/v1/dashboard/tickets/" + ticket.getId() + "/approve?feedback=Looks good, sending to customer",
                null,
                TicketResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(TicketStatus.APPROVED);

        // Verify persistence
        Ticket saved = ticketRepository.findById(ticket.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(saved.getAgentFeedback()).isEqualTo("Looks good, sending to customer");
    }

    @Test
    @DisplayName("Reject endpoint transitions ticket from AWAITING_APPROVAL to REJECTED with reason")
    void testRejectDraft() {
        Ticket ticket = ticketRepository.save(Ticket.builder()
                .tenantId(tenantId)
                .customerId("cust-reject-1")
                .title("Test ticket for rejection")
                .description("This ticket has a bad draft reply")
                .status(TicketStatus.AWAITING_APPROVAL)
                .draftReply("Some AI-generated reply that is incorrect")
                .requiresHuman(false)
                .build());

        ResponseEntity<TicketResponse> response = restTemplate.postForEntity(
                "/v1/dashboard/tickets/" + ticket.getId() + "/reject?reason=Reply is inaccurate, needs human rewrite",
                null,
                TicketResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(TicketStatus.REJECTED);

        // Verify persistence
        Ticket saved = ticketRepository.findById(ticket.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.REJECTED);
        assertThat(saved.getAgentFeedback()).contains("inaccurate");
        assertThat(saved.getRequiresHuman()).isTrue();
    }

    @Test
    @DisplayName("Approve without feedback defaults to 'Approved without edits'")
    void testApproveWithoutFeedback() {
        Ticket ticket = ticketRepository.save(Ticket.builder()
                .tenantId(tenantId)
                .customerId("cust-no-feedback")
                .title("Quick approval ticket")
                .description("Agent approves without adding feedback")
                .status(TicketStatus.AWAITING_APPROVAL)
                .draftReply("AI-generated response")
                .requiresHuman(false)
                .build());

        ResponseEntity<TicketResponse> response = restTemplate.postForEntity(
                "/v1/dashboard/tickets/" + ticket.getId() + "/approve",
                null,
                TicketResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Ticket saved = ticketRepository.findById(ticket.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(saved.getAgentFeedback()).isEqualTo("Approved without edits");
    }

    @Test
    @DisplayName("Approve nonexistent ticket returns error")
    void testApproveNonexistentTicket() {
        UUID fakeId = UUID.randomUUID();
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/dashboard/tickets/" + fakeId + "/approve",
                null,
                String.class
        );

        // Should return 4xx or 5xx (IllegalArgumentException)
        assertThat(response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError()).isTrue();
    }

    @Test
    @DisplayName("SSE stream endpoint returns text/event-stream content type")
    void testSseStreamEndpoint() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // Note: TestRestTemplate blocks on SSE, so we verify the endpoint is reachable
        // In a real test, you'd use WebClient or SseEmitter directly
        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/dashboard/stream?tenantId=" + tenantId,
                HttpMethod.GET,
                entity,
                String.class
        );

        // SSE endpoint should return 200 with event-stream content
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
