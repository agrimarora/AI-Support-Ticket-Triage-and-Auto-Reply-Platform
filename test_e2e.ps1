$ErrorActionPreference = "Stop"

$slug = "globex-" + (Get-Random)
$createTenantBody = "{`"name`": `"Globex Support`", `"slug`": `"$slug`"}"
$tenantResp = Invoke-RestMethod -Uri "http://localhost:8081/v1/admin/tenants" `
  -Method Post `
  -ContentType "application/json" `
  -Body $createTenantBody

$tenantId = $tenantResp.tenantId
$apiKey = $tenantResp.rawApiKey
Write-Host "Tenant created: $tenantId (slug: $slug), API Key: $apiKey" -ForegroundColor Green

Write-Host "`n=== 2. Configuring Budget & Limits ===" -ForegroundColor Cyan
$budgetResp = Invoke-RestMethod -Uri "http://localhost:8081/v1/kb/budget" `
  -Method Post `
  -Headers @{ "X-API-Key" = $apiKey } `
  -ContentType "application/json" `
  -Body '{"dailyTokenLimit": 50000, "rateLimitPerMinute": 100}'
Write-Host "Budget set. Daily limit: $($budgetResp.dailyTokenLimit), Max RPM: $($budgetResp.rateLimitPerMinute)" -ForegroundColor Green

Write-Host "`n=== 3. Ingesting Knowledge Base Document ===" -ForegroundColor Cyan
$docBody = @'
{
  "title": "Authentication & Password Reset Guide",
  "content": "If a user is locked out or the application crashes upon login, ensure the user clears cache and updates to version 2.4. Users can reset their password by visiting https://auth.globex.com/reset. SSO enterprise accounts must authenticate through Okta. Password reset links expire after 15 minutes."
}
'@

$kbResp = Invoke-RestMethod -Uri "http://localhost:8081/v1/kb/documents" `
  -Method Post `
  -Headers @{ "X-API-Key" = $apiKey } `
  -ContentType "application/json" `
  -Body $docBody

Write-Host "Document ingested. ID: $($kbResp.id), Chunks Created: $($kbResp.chunkCount)" -ForegroundColor Green

Write-Host "`n=== 4. Posting Support Ticket (Phase 1 Ingestion + Idempotency) ===" -ForegroundColor Cyan
$ticketBody = @'
{
  "customerId": "user-482",
  "title": "Application crash on login and password reset error",
  "description": "My app closes whenever I try to log in and my password reset link failed."
}
'@

$idemKey = "e2e-req-" + (Get-Random)
$t1 = Invoke-RestMethod -Uri "http://localhost:8081/v1/tickets" `
  -Method Post `
  -Headers @{
    "X-API-Key" = $apiKey
    "Idempotency-Key" = $idemKey
  } `
  -ContentType "application/json" `
  -Body $ticketBody

$ticketId = $t1.ticketId
Write-Host "Ticket created: $ticketId, Status: $($t1.status)" -ForegroundColor Green

Write-Host "`n=== 5. Testing Idempotency (Submitting duplicate request) ===" -ForegroundColor Cyan
$tDuplicate = Invoke-RestMethod -Uri "http://localhost:8081/v1/tickets" `
  -Method Post `
  -Headers @{
    "X-API-Key" = $apiKey
    "Idempotency-Key" = $idemKey
  } `
  -ContentType "application/json" `
  -Body $ticketBody

if ($tDuplicate.ticketId -eq $ticketId) {
  Write-Host "SUCCESS: Idempotency preserved! Same ticket ID returned: $($tDuplicate.ticketId)" -ForegroundColor Green
} else {
  Write-Error "FAILURE: Idempotency failed! Got different ID: $($tDuplicate.ticketId)"
}

Write-Host "`n=== 6. Waiting for Async Outbox -> Kafka -> RAG Worker Processing ===" -ForegroundColor Cyan
Start-Sleep -Seconds 5

Write-Host "`n=== 7. Fetching Processed Ticket (Phase 2 RAG Check) ===" -ForegroundColor Cyan
$processedTicket = Invoke-RestMethod -Uri "http://localhost:8081/v1/tickets/$ticketId" `
  -Method Get `
  -Headers @{ "X-API-Key" = $apiKey }

Write-Host "Ticket Status: $($processedTicket.status)" -ForegroundColor Yellow
Write-Host "Category: $($processedTicket.category), Priority: $($processedTicket.priority)" -ForegroundColor Yellow
Write-Host "Cited Chunks: $($processedTicket.citedChunkIds)" -ForegroundColor Yellow
Write-Host "Draft Reply:" -ForegroundColor Cyan
Write-Host "$($processedTicket.draftReply)" -ForegroundColor White

Write-Host "`n=== 8. Human-In-The-Loop Approval (Agent Dashboard) ===" -ForegroundColor Cyan
$approvedTicket = Invoke-RestMethod -Uri "http://localhost:8081/v1/dashboard/tickets/$ticketId/approve?feedback=Verified%20accurate%20and%20helpful" `
  -Method Post

Write-Host "Ticket Approval Status: $($approvedTicket.status)" -ForegroundColor Green
Write-Host "Agent Feedback: $($approvedTicket.agentFeedback)" -ForegroundColor Green

Write-Host "`n=== 9. Running Evaluation Benchmark (Phase 3 Eval Harness - 100 Tickets) ===" -ForegroundColor Cyan
$evalResp = Invoke-RestMethod -Uri "http://localhost:8081/v1/eval/run?tenantId=$tenantId&promptVersion=v2.1-production" `
  -Method Post

Write-Host "`n================ EVALUATION BENCHMARK REPORT ================" -ForegroundColor Green
Write-Host "Prompt Version:         $($evalResp.promptVersion)"
Write-Host "Total Tickets Evaluated: $($evalResp.totalEvaluated)"
Write-Host "Classification Accuracy: $($evalResp.classificationAccuracy * 100)%"
Write-Host "Retrieval Hit Rate @ 3:  $($evalResp.retrievalHitRateAtK * 100)%"
Write-Host "Average Reply Quality:   $($evalResp.averageReplyQualityScore) / 1.00"
Write-Host "P95 Latency:             $($evalResp.p95LatencyMs) ms"
Write-Host "Average Latency:         $($evalResp.averageLatencyMs) ms"
Write-Host "Circuit Breaker State:   $($evalResp.circuitBreakerStatus)"
Write-Host "=============================================================" -ForegroundColor Green
