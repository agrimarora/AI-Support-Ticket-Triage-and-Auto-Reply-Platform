package com.ticket.domain.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByTenantId(UUID tenantId);

    List<DocumentChunk> findByTenantIdAndDocumentId(UUID tenantId, UUID documentId);

    @Query(value = """
        SELECT id, tenant_id, document_id, chunk_index, content, embedding, created_at,
               (1 - (embedding <=> cast(:queryEmbedding as vector))) AS similarity
        FROM document_chunks
        WHERE tenant_id = :tenantId
          AND (1 - (embedding <=> cast(:queryEmbedding as vector))) >= :similarityThreshold
        ORDER BY similarity DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchSimilarChunksNative(
            @Param("tenantId") UUID tenantId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("similarityThreshold") double similarityThreshold,
            @Param("limit") int limit
    );
}
