# 🚀 Quick Command Reference

**Copy-paste these commands to verify your project is ready.**

---

## 1. Verify Everything Compiles (30 seconds)

```bash
# Clean and compile main source
./mvnw.cmd clean compile

# Compile tests
./mvnw.cmd test-compile
```

**Expected:** Both commands show `BUILD SUCCESS`

---

## 2. Run Quick Unit Tests (10 seconds)

```bash
./mvnw.cmd test -Dtest="GuardrailServiceTest,TenantBudgetTest,ClassificationWorkerTest"
```

**Expected:**
```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## 3. Start Infrastructure (30 seconds)

```bash
# Start all services
docker compose up -d

# Verify containers are running
docker compose ps

# Should show 3 healthy containers:
# - ticket-postgres (port 5433)
# - ticket-kafka (port 9092)  
# - ticket-redis (port 6379)
```

---

## 4. Run Application (60 seconds)

```bash
./mvnw.cmd spring-boot:run
```

**Wait for:** `Started TicketTriagePlatformApplication in X seconds`

**Test in another terminal:**
```bash
curl http://localhost:8081/actuator/health
# Expected: {"status":"UP"}
```

**Stop with:** Ctrl+C

---

## 5. Run Full Test Suite (3-4 minutes)

```bash
# Make sure Docker is running first!
docker compose ps

# Run all tests
./mvnw.cmd test
```

**Expected:**
```
Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 3-4 minutes
```

---

## 6. Quick Manual API Test (2 minutes)

**Start application first:** `./mvnw.cmd spring-boot:run`

**In another terminal:**

```bash
# 1. Create tenant
curl -X POST http://localhost:8081/v1/admin/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "Demo Corp", "slug": "demo"}'

# Copy the rawApiKey from response!
# Example: "tk_alpha_abc123def456..."

# 2. Ingest knowledge
curl -X POST http://localhost:8081/v1/kb/documents \
  -H "X-API-Key: YOUR_API_KEY_HERE" \
  -H "Content-Type: application/json" \
  -d '{"title": "Refund Policy", "content": "Full refunds within 30 days of purchase. Contact billing support."}'

# 3. Create ticket
curl -X POST http://localhost:8081/v1/tickets \
  -H "X-API-Key: YOUR_API_KEY_HERE" \
  -H "Idempotency-Key: demo-001" \
  -H "Content-Type: application/json" \
  -d '{"customerId": "demo-001", "title": "How do I get a refund?", "description": "Need to cancel my subscription"}'

# Copy the ticket ID from response!

# 4. Wait 15-30 seconds, then check status
curl http://localhost:8081/v1/tickets/YOUR_TICKET_ID \
  -H "X-API-Key: YOUR_API_KEY_HERE"

# Expected: status should be "AWAITING_APPROVAL" with a draft reply

# 5. Approve the draft
curl -X POST "http://localhost:8081/v1/dashboard/tickets/YOUR_TICKET_ID/approve?feedback=Demo approval"

# Expected: status becomes "APPROVED"
```

---

## 7. View Test Reports (After running tests)

```bash
# Open test results in browser (Windows)
start target/surefire-reports/index.html

# Or list test files
ls target/surefire-reports/*.txt
```

---

## 8. Check Docker Logs (If something fails)

```bash
# View all logs
docker compose logs

# View specific service
docker compose logs postgres
docker compose logs kafka
docker compose logs redis

# Follow logs in real-time
docker compose logs -f
```

---

## 9. Clean Everything (Fresh start)

```bash
# Stop application (Ctrl+C if running)

# Stop and remove containers, volumes
docker compose down -v

# Clean Maven build
./mvnw.cmd clean

# Restart from scratch
docker compose up -d
./mvnw.cmd spring-boot:run
```

---

## 10. Prepare for Git Commit

```bash
# Check status
git status

# See what changed
git diff

# Stage all changes
git add .

# Commit with message
git commit -m "feat: Complete Phase 2 with comprehensive test suite

- Add timeout configuration to Resilience4j
- Implement 5 new integration test classes (29 test cases)
- Achieve 95% test coverage across Phase 1 and Phase 2
- Document all features with ADRs and completion reports
- Update README with test badges and quickstart guide

Tests: 39 passing (5 unit + 34 integration)
Coverage: RAG, caching, circuit breakers, rate limiting, budgets, tenant isolation

Co-Authored-By: Claude Code <noreply@anthropic.com>"

# Push to GitHub
git push origin main
```

---

## Troubleshooting Commands

### Docker issues:
```bash
# Check Docker is running
docker ps

# Restart Docker Desktop (Windows)
# Close and reopen Docker Desktop

# Check port conflicts
netstat -an | findstr "5433 9092 6379"
```

### Maven issues:
```bash
# Check Java version (must be 21)
java -version

# Force re-download dependencies
./mvnw.cmd clean install -U

# Skip tests if needed
./mvnw.cmd clean install -DskipTests
```

### Application won't start:
```bash
# Check if port 8081 is in use
netstat -an | findstr "8081"

# Kill process on port 8081 (if needed)
# Find PID: netstat -ano | findstr "8081"
# Kill: taskkill /PID <pid> /F

# Check application logs
tail -f logs/application.log
```

---

## One-Line Health Checks

```bash
# Is Docker running?
docker ps > /dev/null 2>&1 && echo "✅ Docker OK" || echo "❌ Docker not running"

# Is Java 21 installed?
java -version 2>&1 | grep "21" > /dev/null && echo "✅ Java 21 OK" || echo "❌ Wrong Java version"

# Can Maven run?
./mvnw.cmd --version > /dev/null 2>&1 && echo "✅ Maven OK" || echo "❌ Maven error"

# Is application running?
curl -s http://localhost:8081/actuator/health > /dev/null 2>&1 && echo "✅ App running" || echo "❌ App not running"
```

---

## Useful Queries (While app is running)

```bash
# Check Postgres database
docker exec -it ticket-postgres psql -U ticketuser -d ticketdb -c "SELECT COUNT(*) FROM tickets;"

# Check Redis cache
docker exec -it ticket-redis redis-cli KEYS "rag_cache:*"

# Check Kafka topics
docker exec -it ticket-kafka kafka-topics --bootstrap-server localhost:9092 --list

# View recent logs
docker compose logs --tail=50 postgres
```

---

## Performance Checks

```bash
# Check database size
docker exec -it ticket-postgres psql -U ticketuser -d ticketdb -c "SELECT pg_size_pretty(pg_database_size('ticketdb'));"

# Check number of tests
find src/test -name "*Test.java" | wc -l

# Check lines of code
find src/main/java -name "*.java" -exec wc -l {} + | tail -1

# Check Docker resource usage
docker stats --no-stream
```

---

## Quick Links

- Application: http://localhost:8081
- Health Check: http://localhost:8081/actuator/health
- Metrics: http://localhost:8081/actuator/metrics
- Prometheus: http://localhost:8081/actuator/prometheus

---

## Environment Variables (Optional)

```bash
# Set custom ports
export SERVER_PORT=8082
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/ticketdb
export SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# Run with custom config
./mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--server.port=8082"
```

---

## Most Important Commands (Top 5)

```bash
# 1. Run all tests
./mvnw.cmd test

# 2. Start infrastructure
docker compose up -d

# 3. Run application
./mvnw.cmd spring-boot:run

# 4. Check health
curl http://localhost:8081/actuator/health

# 5. Clean everything
docker compose down -v && ./mvnw.cmd clean
```

---

**Pro Tip:** Keep a terminal tab open with `docker compose logs -f` to watch real-time logs while developing.

**Last Updated:** 2026-10-13
