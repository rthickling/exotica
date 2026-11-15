package lab;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Actual PostgreSQL integration tests. Pricing is a declared stub here;
 * end-to-end script uses the real service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "lab.fault-injection=true" })
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class WorkflowDatabaseTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "lab"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "lab-local-only"));
    }

    @Autowired
    WorkflowService workflow;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    PricingGateway pricing;

    UUID create(String product) {
        return (UUID) workflow.create(new ApiController.CreateRequest(product, "client-demo", "staff-demo")).get("id");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rfq(UUID id, Map<String, String> modes) {
        return (List<Map<String, Object>>) workflow.rfq(id, new ApiController.RfqRequest(modes, 100)).get("offers");
    }

    UUID prepare(UUID id) {
        UUID indicative = (UUID) rfq(id, Map.of()).getFirst().get("id");
        workflow.select(id, indicative, "Synthetic fixed-payoff comparison exercise");
        workflow.instruct(id, new ApiController.InstructionRequest(true, "synthetic-instruction"));
        return (UUID) workflow.firm(id, "OK").get("id");
    }

    @Test
    void rejectsInvalidFixtureAndIndicativeAcceptance() {
        assertEquals("FIXTURE_NOT_ELIGIBLE", assertThrows(BusinessException.class,
                () -> workflow.create(new ApiController.CreateRequest("AUTOCALLABLE", "wrong", "staff-demo"))).code);
        UUID id = create("AUTOCALLABLE");
        UUID offerId = (UUID) rfq(id, Map.of()).getFirst().get("id");
        var error = assertThrows(BusinessException.class,
                () -> workflow.accept(id, new ApiController.AcceptRequest(offerId, "indicative-" + id, false, null)));
        assertEquals("FIRM_REQUIRED", error.code);
        assertEquals(0, jdbc.queryForObject("select count(*) from trade where enquiry_id=?", Integer.class, id));
    }

    @Test
    void reportsPartialTimeoutAndExpiredAndStaleComparison() {
        UUID id = create("AUTOCALLABLE");
        List<Map<String, Object>> result = rfq(id, Map.of("Issuer-B", "TIMEOUT", "Issuer-C", "ERROR"));
        assertEquals(List.of("AVAILABLE", "TIMEOUT", "ERROR"), result.stream().map(o -> o.get("status")).toList());
        UUID other = create("AUTOCALLABLE");
        List<Map<String, Object>> bad = rfq(other,
                Map.of("Issuer-A", "EXPIRED", "Issuer-B", "STALE", "Issuer-C", "INCOMPARABLE"));
        assertEquals("EXPIRED", assertThrows(BusinessException.class,
                () -> workflow.select(other, (UUID) bad.get(0).get("id"), "expired")).code);
        assertEquals("STALE_TERMS", assertThrows(BusinessException.class,
                () -> workflow.select(other, (UUID) bad.get(1).get("id"), "stale")).code);
        assertEquals("NOT_COMPARABLE", assertThrows(BusinessException.class,
                () -> workflow.select(other, (UUID) bad.get(2).get("id"), "currency changed")).code);
    }

    @Test
    void rollbackLeavesNoTradeAuditOrOutboxAndDuplicateReturnsOriginal() {
        UUID id = create("ACCUMULATOR");
        UUID offerId = prepare(id);
        String key = "rollback-" + id;
        assertEquals("INJECTED_ROLLBACK", assertThrows(BusinessException.class,
                () -> workflow.accept(id, new ApiController.AcceptRequest(offerId, key, true, null))).code);
        assertEquals(0, jdbc.queryForObject("select count(*) from trade where enquiry_id=?", Integer.class, id));
        assertEquals(0,
                jdbc.queryForObject(
                        "select count(*) from audit_event where aggregate_id=? and event_type='TradeBooked'",
                        Integer.class, id));
        var first = workflow.accept(id, new ApiController.AcceptRequest(offerId, key, false, null));
        var repeated = workflow.accept(id, new ApiController.AcceptRequest(offerId, key, false, null));
        assertEquals(first.get("id"), repeated.get("id"));
        UUID anotherFirm = (UUID) workflow.getEnquiry(id).get("selectedOfferId");
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(BusinessException.class,
                () -> workflow.accept(id, new ApiController.AcceptRequest(anotherFirm, key, false, null))).code);
        assertEquals(1, jdbc.queryForObject("select count(*) from trade where enquiry_id=?", Integer.class, id));
        assertEquals(1,
                jdbc.queryForObject("select count(*) from outbox where trade_id=?", Integer.class, first.get("id")));
    }

    @Test
    void genuinelyCompetingTransactionsCannotBookTwice() throws Exception {
        UUID id = create("AUTOCALLABLE");
        UUID offerId = prepare(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // Hold the enquiry row so one acceptance inserts its trade then blocks on the
        // version update;
        // the other transaction attempts its competing INSERT before the lock is
        // released.
        try (Connection lock = DriverManager.getConnection(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "lab"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "lab-local-only"))) {
            lock.setAutoCommit(false);
            try (var stmt = lock.prepareStatement("select id from enquiry where id=? for update")) {
                stmt.setObject(1, id);
                stmt.executeQuery();
            }
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return workflow.accept(id,
                                new ApiController.AcceptRequest(offerId, "race-" + id + "-" + n, false, null));
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }
            start.countDown();
            boolean overlapping = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Integer waiting = jdbc.queryForObject(
                        "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and (query ilike '%update enquiry%' or query ilike '%insert into trade%')",
                        Integer.class);
                if (waiting != null && waiting >= 2) {
                    overlapping = true;
                    break;
                }
                Thread.sleep(20);
            }
            lock.commit();
            List<Object> results = new ArrayList<>();
            for (var f : futures)
                results.add(f.get(10, TimeUnit.SECONDS));
            assertTrue(overlapping,
                    "Must observe two database sessions blocked concurrently; sequential calls do not prove conflict safety");
            assertEquals(1, results.stream().filter(Map.class::isInstance).count());
            assertEquals(1, results.stream().filter(Exception.class::isInstance).count());
            assertEquals(1, jdbc.queryForObject("select count(*) from trade where enquiry_id=?", Integer.class, id));
            assertEquals(1,
                    jdbc.queryForObject(
                            "select count(*) from audit_event where aggregate_id=? and event_type='TradeBooked'",
                            Integer.class, id));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void repeatedLifecycleIsUniqueAndHistoricalChangeIsRejected() throws Exception {
        UUID id = create("ACCUMULATOR");
        UUID offerId = prepare(id);
        UUID tradeId = (UUID) workflow.accept(id, new ApiController.AcceptRequest(offerId, "life-" + id, false, null))
                .get("id");
        JsonNode result = mapper.readTree(
                "{\"termsVersion\":\"acc-v1\",\"terminated\":false,\"processedObservations\":1,\"obligations\":[{\"eventKey\":\"acc-v1:2026-12-16:PURCHASE\",\"observationDate\":\"2026-12-16\",\"paymentDate\":\"2026-12-18\",\"kind\":\"PURCHASE\",\"cash\":-900,\"shares\":10,\"currency\":\"HKD\"}]}");
        when(pricing.call(eq("replay"), any())).thenReturn(result);
        JsonNode fixing = mapper.readTree("{\"2026-12-16\":100}");
        workflow.lifecycle(tradeId, fixing);
        workflow.lifecycle(tradeId, fixing);
        assertEquals(1,
                jdbc.queryForObject("select count(*) from obligation where trade_id=?", Integer.class, tradeId));
        assertEquals("FIXING_CONFLICT", assertThrows(BusinessException.class,
                () -> workflow.lifecycle(tradeId, mapper.readTree("{\"2026-12-16\":110}"))).code);
        workflow.advance(tradeId, LocalDate.of(2026, 12, 17), false);
        assertEquals("DETERMINED",
                jdbc.queryForObject("select state from obligation where trade_id=?", String.class, tradeId));
        workflow.advance(tradeId, LocalDate.of(2026, 12, 18), false);
        assertEquals("DUE",
                jdbc.queryForObject("select state from obligation where trade_id=?", String.class, tradeId));
        workflow.advance(tradeId, LocalDate.of(2026, 12, 18), true);
        assertEquals("SIMULATED_SETTLED",
                jdbc.queryForObject("select state from obligation where trade_id=?", String.class, tradeId));
    }
}
