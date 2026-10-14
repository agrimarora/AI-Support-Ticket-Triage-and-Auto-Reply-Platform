package com.ticket.domain.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationReport {
    private String promptVersion;
    private int totalTicketsEvaluated;
    private double classificationAccuracy;
    private double retrievalHitRateAtK;
    private double averageReplyQualityScore;
    private long p95LatencyMs;
    private double cacheHitRatio;
    private Map<String, Object> details;
}
