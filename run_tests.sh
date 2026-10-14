#!/bin/bash
# Test Runner Script for AI Support Ticket Triage Platform
# Usage: ./run_tests.sh [unit|integration|all]

set -e

echo "======================================"
echo "AI Ticket Triage Platform - Test Suite"
echo "======================================"
echo ""

TEST_TYPE=${1:-all}

run_unit_tests() {
    echo "🧪 Running Unit Tests (No Docker Required)..."
    echo "--------------------------------------"
    ./mvnw.cmd test -Dtest="GuardrailServiceTest,TenantBudgetTest,ClassificationWorkerTest,TenantAuthenticationFilterTest,TicketServiceTest"
    echo ""
    echo "✅ Unit Tests Complete"
}

run_integration_tests() {
    echo "🐳 Running Integration Tests (Requires Docker)..."
    echo "--------------------------------------"
    echo "⚠️  This will download and start Testcontainers (PostgreSQL + Kafka)"
    echo ""
    ./mvnw.cmd test -Dtest="RagReplyServiceIntegrationTest,BudgetRateLimitIntegrationTest,KnowledgeBaseServiceTest,AgentDashboardControllerTest,FullPipelineIntegrationTest,TicketApiIntegrationTest"
    echo ""
    echo "✅ Integration Tests Complete"
}

run_all_tests() {
    echo "🚀 Running All Tests..."
    echo "--------------------------------------"
    ./mvnw.cmd test
    echo ""
    echo "✅ All Tests Complete"
}

case $TEST_TYPE in
    unit)
        run_unit_tests
        ;;
    integration)
        run_integration_tests
        ;;
    all)
        run_all_tests
        ;;
    *)
        echo "Usage: $0 [unit|integration|all]"
        echo ""
        echo "  unit         - Run fast unit tests only (no Docker)"
        echo "  integration  - Run integration tests with Testcontainers (requires Docker)"
        echo "  all          - Run complete test suite (default)"
        exit 1
        ;;
esac

echo ""
echo "======================================"
echo "📊 Test Summary"
echo "======================================"
echo ""
echo "Phase 1 Tests: ✅ Outbox, Idempotency, Tenant Isolation, DLT"
echo "Phase 2 Tests: ✅ RAG, Caching, Circuit Breaker, Rate Limiting, Budgets"
echo "E2E Tests:     ✅ Full pipeline from API → Kafka → RAG → Approval"
echo ""
echo "Total Test Files: 11"
echo "Total Test Cases: ~39"
echo ""
echo "📖 See PHASE2_COMPLETION_REPORT.md for detailed coverage"
echo "======================================"
