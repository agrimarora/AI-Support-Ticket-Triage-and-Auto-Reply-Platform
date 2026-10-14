package com.ticket.domain.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagReplyResult {
    private String draftReply;
    private List<UUID> citedChunkIds;
    private boolean requiresHuman;
    private int promptTokens;
    private int completionTokens;
    private String modelUsed;
    private boolean cacheHit;
}
