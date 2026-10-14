package com.ticket.web;

import com.ticket.domain.eval.EvaluationHarnessService;
import com.ticket.domain.eval.EvaluationReport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/eval")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationHarnessService evaluationHarnessService;

    @PostMapping("/run")
    public ResponseEntity<EvaluationReport> runEvaluation(
            @RequestParam("tenantId") UUID tenantId,
            @RequestParam(value = "promptVersion", defaultValue = "v2.1-production") String promptVersion) {

        EvaluationReport report = evaluationHarnessService.runEvaluation(tenantId, promptVersion);
        return ResponseEntity.ok(report);
    }
}
