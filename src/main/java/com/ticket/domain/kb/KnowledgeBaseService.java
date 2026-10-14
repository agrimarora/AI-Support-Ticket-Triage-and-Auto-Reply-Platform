package com.ticket.domain.kb;

import com.ticket.core.tenant.TenantContext;
import com.ticket.domain.ai.EmbeddingService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class IngestionResult {
        private UUID documentId;
        private String title;
        private int chunkCount;
        private boolean deduplicated;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RetrievedChunk {
        private UUID chunkId;
        private UUID documentId;
        private String content;
        private double similarityScore;
    }

    @Transactional
    public IngestionResult ingestDocument(String title, String rawContent) {
        UUID tenantId = TenantContext.getRequiredTenantId();
        String contentHash = calculateHash(rawContent);

        Optional<KnowledgeDocument> existing = documentRepository.findByTenantIdAndContentHash(tenantId, contentHash);
        if (existing.isPresent()) {
            log.info("Document deduplicated for tenant [{}], hash [{}]", tenantId, contentHash);
            List<DocumentChunk> chunks = chunkRepository.findByTenantIdAndDocumentId(tenantId, existing.get().getId());
            return IngestionResult.builder()
                    .documentId(existing.get().getId())
                    .title(existing.get().getTitle())
                    .chunkCount(chunks.size())
                    .deduplicated(true)
                    .build();
        }

        KnowledgeDocument document = KnowledgeDocument.builder()
                .tenantId(tenantId)
                .title(title)
                .contentHash(contentHash)
                .rawContent(rawContent)
                .build();
        KnowledgeDocument savedDoc = documentRepository.save(document);

        List<String> textChunks = splitIntoChunks(rawContent, 500);
        int index = 0;
        for (String chunkText : textChunks) {
            float[] vector = embeddingService.generateEmbedding(chunkText);
            String vectorStr = embeddingService.toVectorString(vector);

            DocumentChunk chunk = DocumentChunk.builder()
                    .tenantId(tenantId)
                    .documentId(savedDoc.getId())
                    .chunkIndex(index++)
                    .content(chunkText)
                    .embedding(vectorStr)
                    .build();
            chunkRepository.save(chunk);
        }

        log.info("Ingested document [{}] with {} chunks for tenant [{}]", savedDoc.getId(), textChunks.size(), tenantId);
        return IngestionResult.builder()
                .documentId(savedDoc.getId())
                .title(savedDoc.getTitle())
                .chunkCount(textChunks.size())
                .deduplicated(false)
                .build();
    }

    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieveRelevantChunks(UUID tenantId, String query, double similarityThreshold, int limit) {
        float[] queryVector = embeddingService.generateEmbedding(query);
        String queryVectorStr = embeddingService.toVectorString(queryVector);

        List<RetrievedChunk> results = new ArrayList<>();
        try {
            List<Object[]> rows = chunkRepository.searchSimilarChunksNative(tenantId, queryVectorStr, similarityThreshold, limit);
            for (Object[] row : rows) {
                UUID id = row[0] instanceof UUID ? (UUID) row[0] : UUID.fromString(row[0].toString());
                UUID docId = row[2] instanceof UUID ? (UUID) row[2] : UUID.fromString(row[2].toString());
                String content = (String) row[4];
                double similarity = ((Number) row[7]).doubleValue();

                results.add(RetrievedChunk.builder()
                        .chunkId(id)
                        .documentId(docId)
                        .content(content)
                        .similarityScore(similarity)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Vector search error, falling back to keyword substring matching", e);
            List<DocumentChunk> allChunks = chunkRepository.findByTenantId(tenantId);
            String lowerQuery = query.toLowerCase();
            for (DocumentChunk chunk : allChunks) {
                if (chunk.getContent().toLowerCase().contains(lowerQuery)) {
                    results.add(RetrievedChunk.builder()
                            .chunkId(chunk.getId())
                            .documentId(chunk.getDocumentId())
                            .content(chunk.getContent())
                            .similarityScore(0.85)
                            .build());
                }
            }
        }

        return results;
    }

    private List<String> splitIntoChunks(String text, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        String[] paragraphs = text.split("\n\n");
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (current.length() + paragraph.length() > maxChunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(paragraph).append("\n\n");
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private String calculateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 digest unavailable", e);
        }
    }
}
