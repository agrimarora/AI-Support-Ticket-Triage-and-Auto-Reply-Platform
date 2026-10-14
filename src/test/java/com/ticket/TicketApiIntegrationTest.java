package com.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.security.ApiKeyHasher;
import com.ticket.domain.tenant.ApiKey;
import com.ticket.domain.tenant.ApiKeyRepository;
import com.ticket.domain.tenant.Tenant;
import com.ticket.domain.tenant.TenantRepository;
import com.ticket.domain.ticket.Ticket;
import com.ticket.domain.ticket.TicketRepository;
import com.ticket.domain.ticket.TicketStatus;
import com.ticket.dto.CreateTicketRequest;
import com.ticket.dto.TicketResponse;
import com.ticket.outbox.OutboxPublisherScheduler;
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

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public class TicketApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("ticketdb_test")
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
    private OutboxPublisherScheduler outboxPublisherScheduler;

    @MockBean
    private StringRedisTemplate redisTemplate;

    private String tenantARawKey;
    private Tenant tenantA;
    private String tenantBRawKey;
    private Tenant tenantB;

    @BeforeEach
    void setUp() {
        // Mock Redis value ops so filter falls back cleanly or succeeds
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);

        ticketRepository.deleteAll();
        apiKeyRepository.deleteAll();
        tenantRepository.deleteAll();

        // Seed Tenant A
        tenantA = tenantRepository.save(Tenant.builder().name("Tenant Alpha").slug("alpha").status("ACTIVE").build());
        tenantARawKey = "tk_alpha_" + UUID.randomUUID().toString().replace("-", "");
        apiKeyRepository.save(ApiKey.builder()
                .tenantId(tenantA.getId())
                .name("Alpha Key")
                .keyHash(ApiKeyHasher.hashApiKey(tenantARawKey))
                .revoked(false)
                .build());

        // Seed Tenant B
        tenantB = tenantRepository.save(Tenant.builder().name("Tenant Beta").slug("beta").status("ACTIVE").build());
        tenantBRawKey = "tk_beta_" + UUID.randomUUID().toString().replace("-", "");
        apiKeyRepository.save(ApiKey.builder()
                .tenantId(tenantB.getId())
                .name("Beta Key")
                .keyHash(ApiKeyHasher.hashApiKey(tenantBRawKey))
                .revoked(false)
                .build());
    }

    @Test
    @DisplayName("Tenant Isolation: Tenant B cannot read Tenant A tickets")
    void testTenantIsolation() {
        // 1. Post ticket as Tenant A
        HttpHeaders headersA = new HttpHeaders();
        headersA.set("X-API-Key", tenantARawKey);
        headersA.setContentType(MediaType.APPLICATION_JSON);

        CreateTicketRequest request = CreateTicketRequest.builder()
                .customerId("cust-101")
                .title("Alpha confidential ticket")
                .description("Secret info for Alpha")
                .build();

        HttpEntity<CreateTicketRequest> entityA = new HttpEntity<>(request, headersA);
        ResponseEntity<TicketResponse> postResponse = restTemplate.postForEntity("/v1/tickets", entityA, TicketResponse.class);

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID ticketId = postResponse.getBody().getId();

        // 2. Try to get Ticket A using Tenant B's API Key -> Returns 404 Not Found
        HttpHeaders headersB = new HttpHeaders();
        headersB.set("X-API-Key", tenantBRawKey);
        HttpEntity<Void> entityB = new HttpEntity<>(headersB);

        ResponseEntity<String> getResponse = restTemplate.exchange("/v1/tickets/" + ticketId, HttpMethod.GET, entityB, String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 3. GET /v1/tickets as Tenant B returns 0 tickets
        ResponseEntity<String> listResponse = restTemplate.exchange("/v1/tickets", HttpMethod.GET, entityB, String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).contains("\"totalElements\":0");
    }

    @Test
    @DisplayName("Idempotency: Sending identical Idempotency-Key returns duplicate ticket response")
    void testIdempotencyKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", tenantARawKey);
        headers.set("Idempotency-Key", "idem-unique-12345");
        headers.setContentType(MediaType.APPLICATION_JSON);

        CreateTicketRequest request1 = CreateTicketRequest.builder()
                .customerId("cust-202")
                .title("Payment failed for order")
                .description("Credit card was declined twice")
                .build();

        HttpEntity<CreateTicketRequest> entity1 = new HttpEntity<>(request1, headers);
        ResponseEntity<TicketResponse> res1 = restTemplate.postForEntity("/v1/tickets", entity1, TicketResponse.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID firstTicketId = res1.getBody().getId();

        // Send duplicate request with SAME Idempotency-Key but different title
        CreateTicketRequest request2 = CreateTicketRequest.builder()
                .customerId("cust-202")
                .title("DIFFERENT TITLE SHOULD BE IGNORED")
                .description("DIFFERENT DESCRIPTION")
                .build();

        HttpEntity<CreateTicketRequest> entity2 = new HttpEntity<>(request2, headers);
        ResponseEntity<TicketResponse> res2 = restTemplate.postForEntity("/v1/tickets", entity2, TicketResponse.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID secondTicketId = res2.getBody().getId();

        assertThat(firstTicketId).isEqualTo(secondTicketId);
        assertThat(res2.getBody().getTitle()).isEqualTo("Payment failed for order");
    }

    @Test
    @DisplayName("End-to-End Pipeline: Ticket posted to API ends up classified in database via Kafka")
    void testEndToEndTicketPipeline() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", tenantARawKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        CreateTicketRequest request = CreateTicketRequest.builder()
                .customerId("cust-303")
                .title("Critical bug: app crash on login")
                .description("The mobile application freezes and crashes immediately on start")
                .build();

        HttpEntity<CreateTicketRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<TicketResponse> response = restTemplate.postForEntity("/v1/tickets", entity, TicketResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID ticketId = response.getBody().getId();

        // Trigger outbox publisher explicitly to dispatch outbox event to Kafka
        outboxPublisherScheduler.publishPendingOutboxEvents();

        // Await Kafka Consumer full pipeline: classify → RAG draft → AWAITING_APPROVAL
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.AWAITING_APPROVAL);
            assertThat(ticket.getCategory()).isEqualTo("TECHNICAL_BUG");
            assertThat(ticket.getPriority()).isNotNull();
            assertThat(ticket.getConfidenceScore()).isGreaterThan(0.8);
            assertThat(ticket.getDraftReply()).isNotNull();
        });
    }
}
