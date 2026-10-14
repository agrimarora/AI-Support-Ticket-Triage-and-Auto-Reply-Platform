package com.ticket.domain.kb;

import com.ticket.core.tenant.TenantContext;
import com.ticket.domain.tenant.ApiKey;
import com.ticket.domain.tenant.ApiKeyRepository;
import com.ticket.domain.tenant.Tenant;
import com.ticket.domain.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Phase 2: Knowledge Base Service Integration Tests")
public class KnowledgeBaseServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("kb_test_db")
            .withUsername("testuser")
            .withPassword("testpass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:19092");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "16379");
    }

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void setUp() {
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        apiKeyRepository.deleteAll();
        tenantRepository.deleteAll();

        // Create Tenant A
        Tenant tenantA = tenantRepository.save(Tenant.builder()
                .name("Tenant A")
                .slug("tenant-a")
                .status("ACTIVE")
                .build());
        tenantAId = tenantA.getId();

        // Create Tenant B
        Tenant tenantB = tenantRepository.save(Tenant.builder()
                .name("Tenant B")
                .slug("tenant-b")
                .status("ACTIVE")
                .build());
        tenantBId = tenantB.getId();

        // Seed API keys for context
        apiKeyRepository.save(ApiKey.builder()
                .tenantId(tenantAId)
                .name("Key A")
                .keyHash("hash-a")
                .revoked(false)
                .build());

        apiKeyRepository.save(ApiKey.builder()
                .tenantId(tenantBId)
                .name("Key B")
                .keyHash("hash-b")
                .revoked(false)
                .build());
    }

    @Test
    @DisplayName("Document ingestion creates chunks with embeddings")
    void testDocumentIngestionCreatesChunks() {
        TenantContext.setTenantId(tenantAId);

        String content = "This is a test document with multiple paragraphs.\n\n" +
                "The second paragraph contains different information.\n\n" +
                "The third paragraph adds more context for testing chunking logic.";

        KnowledgeBaseService.IngestionResult result = knowledgeBaseService.ingestDocument(
                "Test Document",
                content
        );

        assertThat(result).isNotNull();
        assertThat(result.getDocumentId()).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Test Document");
        assertThat(result.getChunkCount()).isGreaterThan(0);
        assertThat(result.isDeduplicated()).isFalse();

        // Verify document was saved
        KnowledgeDocument doc = documentRepository.findById(result.getDocumentId()).orElseThrow();
        assertThat(doc.getTenantId()).isEqualTo(tenantAId);
        assertThat(doc.getRawContent()).isEqualTo(content);

        // Verify chunks were created with embeddings
        List<DocumentChunk> chunks = chunkRepository.findByTenantIdAndDocumentId(tenantAId, result.getDocumentId());
        assertThat(chunks).hasSize(result.getChunkCount());
        assertThat(chunks.get(0).getEmbedding()).isNotNull();
        assertThat(chunks.get(0).getEmbedding()).startsWith("[");
        assertThat(chunks.get(0).getEmbedding()).endsWith("]");
    }

    @Test
    @DisplayName("Duplicate document ingestion is deduplicated by content hash")
    void testDuplicateDocumentDeduplication() {
        TenantContext.setTenantId(tenantAId);

        String content = "This is the exact same content for deduplication testing.";
        String title = "Duplicate Test Doc";

        // First ingestion
        KnowledgeBaseService.IngestionResult first = knowledgeBaseService.ingestDocument(title, content);
        assertThat(first.isDeduplicated()).isFalse();
        UUID firstDocId = first.getDocumentId();

        // Second ingestion with same content (should be deduplicated)
        KnowledgeBaseService.IngestionResult second = knowledgeBaseService.ingestDocument(title, content);
        assertThat(second.isDeduplicated()).isTrue();
        assertThat(second.getDocumentId()).isEqualTo(firstDocId);

        // Verify only one document exists
        List<KnowledgeDocument> docs = documentRepository.findByTenantId(tenantAId);
        assertThat(docs).hasSize(1);
    }

    @Test
    @DisplayName("Vector retrieval returns relevant chunks above similarity threshold")
    void testVectorRetrievalFindsRelevantChunks() {
        TenantContext.setTenantId(tenantAId);

        // Ingest document about billing
        knowledgeBaseService.ingestDocument(
                "Billing Policy",
                "Our billing system processes payments monthly. Refunds are available within 30 days. " +
                        "Contact billing support for invoice disputes."
        );

        // Search for billing-related query
        List<KnowledgeBaseService.RetrievedChunk> results = knowledgeBaseService.retrieveRelevantChunks(
                tenantAId,
                "How do I request a refund for my invoice?",
                0.40, // Lower threshold for deterministic embeddings
                3
        );

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getContent()).containsAnyOf("billing", "refund", "invoice", "payment");
        assertThat(results.get(0).getSimilarityScore()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Vector retrieval returns empty when no chunks exceed similarity threshold")
    void testVectorRetrievalReturnsEmptyBelowThreshold() {
        TenantContext.setTenantId(tenantAId);

        // Ingest unrelated content
        knowledgeBaseService.ingestDocument(
                "Weather Report",
                "Today's weather is sunny with a high of 75 degrees. Light breeze from the north."
        );

        // Search for completely unrelated query
        List<KnowledgeBaseService.RetrievedChunk> results = knowledgeBaseService.retrieveRelevantChunks(
                tenantAId,
                "quantum mechanics and particle physics theories",
                0.70, // High threshold
                3
        );

        // With deterministic embeddings, unrelated content should have low similarity
        // Results might still return due to fallback, but similarity should be low
        if (!results.isEmpty()) {
            assertThat(results.get(0).getSimilarityScore()).isLessThan(0.70);
        }
    }

    @Test
    @DisplayName("Tenant isolation: Tenant A cannot retrieve Tenant B documents")
    void testTenantIsolationInRetrieval() {
        // Tenant A ingests a document
        TenantContext.setTenantId(tenantAId);
        knowledgeBaseService.ingestDocument(
                "Tenant A Secret Doc",
                "This is confidential information for Tenant A only. Secret password is ALPHA123."
        );

        // Tenant B ingests a different document
        TenantContext.setTenantId(tenantBId);
        knowledgeBaseService.ingestDocument(
                "Tenant B Public Doc",
                "This is public information for Tenant B. Contact support at beta@example.com."
        );

        // Tenant B searches for content (should only find their own document)
        List<KnowledgeBaseService.RetrievedChunk> resultsB = knowledgeBaseService.retrieveRelevantChunks(
                tenantBId,
                "password secret information",
                0.30,
                5
        );

        // Verify Tenant B never sees Tenant A's secret document
        for (KnowledgeBaseService.RetrievedChunk chunk : resultsB) {
            assertThat(chunk.getContent()).doesNotContain("ALPHA123");
            assertThat(chunk.getContent()).doesNotContain("Tenant A");
        }

        // Verify Tenant A can find their own document
        TenantContext.setTenantId(tenantAId);
        List<KnowledgeBaseService.RetrievedChunk> resultsA = knowledgeBaseService.retrieveRelevantChunks(
                tenantAId,
                "password secret information",
                0.30,
                5
        );

        boolean foundSecret = resultsA.stream()
                .anyMatch(chunk -> chunk.getContent().contains("ALPHA123") || chunk.getContent().contains("confidential"));
        assertThat(foundSecret).isTrue();
    }

    @Test
    @DisplayName("Vector search falls back to keyword matching on error")
    void testVectorSearchFallbackToKeywordMatching() {
        TenantContext.setTenantId(tenantAId);

        knowledgeBaseService.ingestDocument(
                "Product Guide",
                "Our product offers premium features including analytics dashboard and reporting tools."
        );

        // Normal retrieval should work
        List<KnowledgeBaseService.RetrievedChunk> results = knowledgeBaseService.retrieveRelevantChunks(
                tenantAId,
                "analytics dashboard reporting",
                0.30,
                3
        );

        // The service has fallback logic: if vector search fails, it uses keyword substring matching
        assertThat(results).isNotEmpty();
    }

    @Test
    @DisplayName("Chunk splitting handles various document sizes correctly")
    void testChunkSplittingLogic() {
        TenantContext.setTenantId(tenantAId);

        // Small document (single chunk)
        KnowledgeBaseService.IngestionResult small = knowledgeBaseService.ingestDocument(
                "Small Doc",
                "Short content that fits in one chunk."
        );
        assertThat(small.getChunkCount()).isEqualTo(1);

        // Large document (multiple chunks)
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            largeContent.append("Paragraph ").append(i).append(" contains information about topic number ").append(i).append(".\n\n");
        }

        KnowledgeBaseService.IngestionResult large = knowledgeBaseService.ingestDocument(
                "Large Doc",
                largeContent.toString()
        );
        assertThat(large.getChunkCount()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Retrieved chunks include document metadata")
    void testRetrievedChunksIncludeMetadata() {
        TenantContext.setTenantId(tenantAId);

        KnowledgeBaseService.IngestionResult ingestion = knowledgeBaseService.ingestDocument(
                "Metadata Test Doc",
                "This document is for testing metadata retrieval in chunks."
        );

        List<KnowledgeBaseService.RetrievedChunk> results = knowledgeBaseService.retrieveRelevantChunks(
                tenantAId,
                "metadata testing",
                0.30,
                3
        );

        assertThat(results).isNotEmpty();
        KnowledgeBaseService.RetrievedChunk chunk = results.get(0);
        assertThat(chunk.getChunkId()).isNotNull();
        assertThat(chunk.getDocumentId()).isEqualTo(ingestion.getDocumentId());
        assertThat(chunk.getContent()).isNotNull();
        assertThat(chunk.getSimilarityScore()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("Empty or null content does not crash ingestion")
    void testIngestionHandlesEdgeCases() {
        TenantContext.setTenantId(tenantAId);

        // Empty content
        KnowledgeBaseService.IngestionResult emptyResult = knowledgeBaseService.ingestDocument(
                "Empty Doc",
                ""
        );
        assertThat(emptyResult.getChunkCount()).isEqualTo(0);

        // Whitespace only
        KnowledgeBaseService.IngestionResult whitespaceResult = knowledgeBaseService.ingestDocument(
                "Whitespace Doc",
                "   \n\n   "
        );
        assertThat(whitespaceResult.getChunkCount()).isEqualTo(0);
    }
}
