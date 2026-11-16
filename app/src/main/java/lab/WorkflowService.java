package lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static lab.JsonSupport.fields;

/**
 * The transaction boundary is this bean's public method, invoked by the
 * controller proxy.
 */
@Service
public class WorkflowService {
    private final EnquiryRepository enquiries;
    private final TermRepository terms;
    private final OfferRepository offers;
    private final TradeRepository trades;
    private final AuditRepository audits;
    private final OutboxRepository outbox;
    private final ObligationRepository obligations;
    private final ValuationRepository valuations;
    private final LifecycleFixingRepository lifecycleFixings;
    private final ProductCatalog catalog;
    private final IssuerGateway issuerGateway;
    private final PricingGateway pricer;
    private final JsonSupport json;
    private final Clock clock;
    private final boolean faultInjection;
    private final ExecutorService issuerExecutor = Executors.newFixedThreadPool(3);

    WorkflowService(EnquiryRepository enquiries, TermRepository terms, OfferRepository offers, TradeRepository trades,
            AuditRepository audits, OutboxRepository outbox, ObligationRepository obligations,
            ValuationRepository valuations, LifecycleFixingRepository lifecycleFixings, ProductCatalog catalog,
            IssuerGateway issuerGateway, PricingGateway pricer, JsonSupport json, Clock clock,
            @Value("${lab.fault-injection}") boolean faultInjection) {
        this.enquiries = enquiries;
        this.terms = terms;
        this.offers = offers;
        this.trades = trades;
        this.audits = audits;
        this.outbox = outbox;
        this.obligations = obligations;
        this.valuations = valuations;
        this.lifecycleFixings = lifecycleFixings;
        this.catalog = catalog;
        this.issuerGateway = issuerGateway;
        this.pricer = pricer;
        this.json = json;
        this.clock = clock;
        this.faultInjection = faultInjection;
    }

    @PreDestroy
    void shutdown() {
        issuerExecutor.shutdownNow();
    }

    @Transactional
    public Map<String, Object> create(ApiController.CreateRequest request) {
        if (!"client-demo".equals(request.clientId()) || !"staff-demo".equals(request.operatorId()))
            throw new BusinessException(403, "FIXTURE_NOT_ELIGIBLE",
                    "Only authorised fixture staff-demo and eligible client-demo may create enquiries");
        JsonNode contract = catalog.terms(request.product());
        saveTerms(contract);
        Enquiry e = new Enquiry();
        e.id = UUID.randomUUID();
        e.product = request.product();
        e.termsVersion = contract.path("termsVersion").asText();
        e.termsJson = json.write(contract);
        e.termsHash = JsonSupport.hash(e.termsJson);
        e.state = "ENQUIRY";
        e.clientId = request.clientId();
        e.clientReference = request.clientReference();
        e.operatorId = request.operatorId();
        e.eligibility = "SYNTHETIC_HK_CLIENT_AND_PRODUCT_APPROVED";
        e.createdAt = clock.instant();
        enquiries.save(e);
        audit(e.id, "EnquiryCreated",
                fields("operatorId", e.operatorId, "termsVersion", e.termsVersion, "eligibility", e.eligibility));
        return enquiryDto(e);
    }

    private void saveTerms(JsonNode contract) {
        String version = contract.path("termsVersion").asText();
        String encoded = json.write(contract);
        String hash = JsonSupport.hash(encoded);
        if (version.isBlank() || version.length() > 100)
            throw new BusinessException(400, "INVALID_TERMS_VERSION", "termsVersion is required (max100 characters)");
        var old = terms.findById(version);
        if (old.isPresent()) {
            if (!old.get().termsHash.equals(hash))
                throw new BusinessException(409, "IMMUTABLE_TERMS",
                        "This termsVersion already identifies different terms");
        } else {
            TermVersion t = new TermVersion();
            t.id = version;
            t.termsJson = encoded;
            t.termsHash = hash;
            terms.save(t);
        }
    }

    @Transactional
    public Map<String, Object> revise(@NonNull UUID id, JsonNode contract) {
        Enquiry e = enquiry(id);
        requireUnbooked(e);
        if (!contract.path("product").asText().equals(e.product))
            throw new BusinessException(400, "PRODUCT_CHANGED", "Create a separate enquiry for another product");
        if (contract.path("termsVersion").asText().equals(e.termsVersion))
            throw new BusinessException(409, "NEW_VERSION_REQUIRED", "A revision needs a new immutable termsVersion");
        // The pricer independently validates every term before the revision becomes
        // usable.
        pricer.call("replay", json.mapper.valueToTree(fields("terms", contract, "fixings", Map.of())));
        saveTerms(contract);
        e.termsVersion = contract.path("termsVersion").asText();
        e.termsJson = json.write(contract);
        e.termsHash = JsonSupport.hash(e.termsJson);
        e.selectedOfferId = null;
        e.selectionRationale = null;
        e.instructionJson = null;
        e.state = "ENQUIRY";
        audit(e.id, "TermsRevised",
                fields("termsVersion", e.termsVersion, "termsHash", e.termsHash, "oldComparisonsInvalid", true));
        enquiries.flush();
        return enquiryDto(e);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getEnquiry(@NonNull UUID id) {
        Enquiry e = enquiry(id);
        Map<String, Object> result = enquiryDto(e);
        result.put("offers", offers.findByEnquiryIdOrderByReceivedAtAsc(id).stream().map(this::offerDto).toList());
        result.put("audit", audits.findByAggregateId(id).stream().map(this::auditDto).toList());
        result.put("valuations", valuations.findByEnquiryId(id).stream().map(v -> fields("id", v.id, "createdAt",
                v.createdAt, "request", json.read(v.requestJson), "result", json.read(v.resultJson))).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> rfq(@NonNull UUID id, ApiController.RfqRequest request) {
        Enquiry e = enquiry(id);
        requireUnbooked(e);
        Map<String, String> scenarios = request.scenarios() == null ? Map.of() : request.scenarios();
        Set<String> valid = Set.of("OK", "TIMEOUT", "ERROR", "EXPIRED", "STALE", "INCOMPARABLE", "DECLINED",
                "UNSUPPORTED");
        if (!Set.of("Issuer-A", "Issuer-B", "Issuer-C").containsAll(scenarios.keySet())
                || !scenarios.values().stream().allMatch(valid::contains))
            throw new BusinessException(400, "INVALID_SCENARIO",
                    "Scenarios accept Issuer-A/B/C and OK,TIMEOUT,ERROR,EXPIRED,STALE,INCOMPARABLE,DECLINED,UNSUPPORTED");
        int deadline = request.deadlineMillis() == null ? 200 : request.deadlineMillis();
        if (deadline < 10 || deadline > 1000)
            throw new BusinessException(400, "INVALID_DEADLINE", "deadlineMillis must be10..1000");
        UUID requestId = UUID.randomUUID();
        Instant now = clock.instant();
        List<String> issuers = List.of("Issuer-A", "Issuer-B", "Issuer-C");
        List<Callable<IssuerGateway.Quote>> tasks = issuers.stream().<Callable<IssuerGateway.Quote>>map(
                issuer -> () -> issuerGateway.quote(new IssuerGateway.QuoteRequest(requestId, issuer, e.product,
                        e.termsVersion, e.termsHash, e.termsJson, json.read(e.termsJson).path("currency").asText(),
                        size(e), scenarios.getOrDefault(issuer, "OK"), now)))
                .toList();
        List<Offer> result = new ArrayList<>();
        try {
            // Each task starts in a bounded pool. The one deadline is measured from
            // dispatch, not sequential waits.
            List<Future<IssuerGateway.Quote>> futures = issuerExecutor.invokeAll(tasks, deadline,
                    TimeUnit.MILLISECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Offer o;
                try {
                    o = fromQuote(e, requestId, issuers.get(i), futures.get(i).get(), "INDICATIVE");
                } catch (CancellationException ex) {
                    o = failedOffer(e, requestId, issuers.get(i), "TIMEOUT", "Per-issuer dispatch deadline exceeded");
                } catch (ExecutionException ex) {
                    o = failedOffer(e, requestId, issuers.get(i), "ERROR", "Synthetic issuer failure");
                }
                offers.save(Objects.requireNonNull(o));
                result.add(Objects.requireNonNull(o));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "RFQ_INTERRUPTED", "Issuer enquiry interrupted");
        }
        audit(id, "IndicativeOffersRequested", fields("requestId", requestId, "deadlineMillis", deadline, "offers",
                result.stream().map(this::offerDto).toList()));
        return fields("requestId", requestId, "enquiryId", id, "deadlineMillis", deadline, "offers",
                result.stream().map(this::offerDto).toList(), "simulated", true);
    }

    @Transactional
    public Map<String, Object> compare(@NonNull UUID id) {
        Enquiry e = enquiry(id);
        Instant now = clock.instant();
        Map<String, Object> comparison = fields("basis",
                fields("termsVersion", e.termsVersion, "termsHash", e.termsHash, "terms", json.read(e.termsJson),
                        "size", size(e), "currency", json.read(e.termsJson).path("currency").asText(), "asOf", now),
                "offers",
                offers.findByEnquiryIdOrderByReceivedAtAsc(id).stream().filter(o -> o.kind.equals("INDICATIVE"))
                        .map(o -> fields("offer", offerDto(o), "comparability", comparability(e, o, now),
                                "netUpfrontCost", comparability(e, o, now).equals("COMPARABLE") ? netCost(e, o) : null))
                        .toList(),
                "interpretation",
                "SIMULATED fixed-payoff comparison only: lower upfront investor cost for identical terms/size/currency/fees. Issuer exposure is separate; this is not investment suitability or execution.");
        audit(id, "OffersCompared", comparison);
        return comparison;
    }

    @Transactional
    public Map<String, Object> match(@NonNull UUID id, String outcome) {
        Enquiry e = enquiry(id);
        requireUnbooked(e);
        if (!Set.of("IMPROVED", "MATCHED", "UNIMPROVED", "DECLINED").contains(outcome))
            throw new BusinessException(400, "INVALID_MATCH", "Choose IMPROVED, MATCHED, UNIMPROVED or DECLINED");
        Offer best = offers.findByEnquiryIdOrderByReceivedAtAsc(id).stream()
                .filter(o -> o.kind.equals("INDICATIVE") && !o.issuer.equals("Barclays-simulated")
                        && comparability(e, o, clock.instant()).equals("COMPARABLE"))
                .min(Comparator.comparing(o -> netCost(e, o))).orElseThrow(() -> new BusinessException(409,
                        "NO_COMPARABLE_OFFER", "No current comparable external indicative offer"));
        Offer o = copyOffer(e, best, "INDICATIVE");
        o.issuer = "Barclays-simulated";
        BigDecimal adjustment = switch (outcome) {
        case "IMPROVED" -> new BigDecimal("-10");
        case "UNIMPROVED" -> new BigDecimal("10");
        default -> BigDecimal.ZERO;
        };
        o.fee = best.fee.add(adjustment);
        o.status = outcome.equals("DECLINED") ? "DECLINED" : "AVAILABLE";
        o.details = "SIMULATED commercial outcome=" + outcome
                + "; no assertion about Barclays capability, external-product distribution or credit";
        offers.save(o);
        audit(id, "SyntheticMatchingResponse",
                fields("outcome", outcome, "comparedOfferId", best.id, "offer", offerDto(o)));
        return fields("outcome", outcome, "offer", offerDto(o));
    }

    @Transactional
    public Map<String, Object> select(@NonNull UUID id, @NonNull UUID offerId, String rationale) {
        Enquiry e = enquiry(id);
        requireUnbooked(e);
        Offer o = offer(offerId, id);
        requireComparable(e, o);
        if (!o.kind.equals("INDICATIVE"))
            throw new BusinessException(409, "NOT_INDICATIVE", "Selection records an indicative comparison");
        e.selectedOfferId = o.id;
        e.selectionRationale = rationale;
        e.instructionJson = null;
        e.state = "SELECTED";
        audit(id, "IndicativeOfferSelected", fields("offerId", offerId, "rationale", rationale));
        enquiries.flush();
        return enquiryDto(e);
    }

    @Transactional
    public Map<String, Object> instruct(@NonNull UUID id, ApiController.InstructionRequest request) {
        Enquiry e = enquiry(id);
        requireUnbooked(e);
        if (!request.authorised())
            throw new BusinessException(403, "AUTHORITY_REQUIRED", "Simulated authorised instruction is required");
        UUID selectedId = e.selectedOfferId;
        if (selectedId == null)
            throw new BusinessException(409, "SELECTION_REQUIRED", "Select a comparable indicative offer first");
        requireComparable(e, offer(selectedId, id));
        e.instructionJson = json.write(
                fields("authorised", true, "reference", request.reference(), "offerId", e.selectedOfferId, "termsHash",
                        e.termsHash, "operatorId", e.operatorId, "recordedAt", clock.instant(), "simulated", true));
        e.state = "INSTRUCTED";
        audit(id, "SimulatedInstructionRecorded", json.read(e.instructionJson));
        enquiries.flush();
        return enquiryDto(e);
    }

    @Transactional
    public Map<String, Object> firm(@NonNull UUID id, String scenario) {
        Enquiry e = enquiry(id);
        requireUnbooked(e);
        UUID selectedId = e.selectedOfferId;
        if (e.instructionJson == null || selectedId == null)
            throw new BusinessException(409, "INSTRUCTION_REQUIRED",
                    "Record authorised instruction before requesting a firm offer");
        Offer selected = offer(selectedId, id);
        requireComparable(e, selected);
        if (!Set.of("OK", "EXPIRED", "DECLINED", "STALE").contains(scenario))
            throw new BusinessException(400, "INVALID_FIRM_SCENARIO", "Choose OK,EXPIRED,DECLINED,STALE");
        Offer o = copyOffer(e, selected, "FIRM");
        o.sourceOfferId = selected.id;
        o.expiresAt = clock.instant().plusSeconds(scenario.equals("EXPIRED") ? -1 : 300);
        if (scenario.equals("DECLINED"))
            o.status = "DECLINED";
        if (scenario.equals("STALE"))
            o.termsHash = "stale-firm-hash";
        offers.save(o);
        audit(id, "SimulatedFirmOfferReceived", offerDto(o));
        return offerDto(o);
    }

    @Transactional
    public Map<String, Object> accept(@NonNull UUID id, ApiController.AcceptRequest request) {
        Optional<Trade> existing = trades.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Trade t = existing.get();
            if (!t.enquiryId.equals(id) || !t.offerId.equals(request.offerId()))
                throw new BusinessException(409, "IDEMPOTENCY_CONFLICT",
                        "Key already belongs to another acceptance request");
            return tradeDto(t, false);
        }
        Enquiry e = enquiry(id);
        requireUnbooked(e);
        Offer firm = offer(request.offerId(), id);
        if (!"FIRM".equals(firm.kind))
            throw new BusinessException(409, "FIRM_REQUIRED", "An indicative offer cannot execute or book a trade");
        if (e.instructionJson == null)
            throw new BusinessException(409, "INSTRUCTION_REQUIRED", "No authorised simulated instruction");
        if (!Objects.equals(firm.sourceOfferId, e.selectedOfferId))
            throw new BusinessException(409, "SELECTION_CHANGED", "Firm offer does not match the instructed selection");
        requireComparable(e, firm);
        if (request.expectedVersion() != null && request.expectedVersion() != e.version)
            throw new BusinessException(409, "VERSION_CONFLICT", "Enquiry changed since the caller read it");
        Trade t = new Trade();
        t.id = UUID.randomUUID();
        t.enquiryId = id;
        t.idempotencyKey = request.idempotencyKey();
        t.offerId = firm.id;
        t.product = e.product;
        t.termsVersion = e.termsVersion;
        t.termsJson = e.termsJson;
        t.state = "SIMULATED_BOOKED";
        t.bookedAt = clock.instant();
        JsonNode contract = json.read(e.termsJson);
        if (e.product.equals("AUTOCALLABLE")) {
            t.originalPurchasePrice = netCost(e, firm);
            t.maximumPurchaseExposure = null;
        } else {
            t.originalPurchasePrice = firm.fee;
            t.maximumPurchaseExposure = contract.path("strike").decimalValue()
                    .multiply(contract.path("baseQuantity").decimalValue())
                    .multiply(new BigDecimal(contract.path("multiplier").asInt()))
                    .multiply(new BigDecimal(contract.path("observationDates").size()));
        }
        trades.saveAndFlush(t); // rollback drill proves even a flushed INSERT is uncommitted
        if (request.failAfterTrade()) {
            if (!faultInjection)
                throw new BusinessException(403, "FAULT_DISABLED",
                        "Fault injection is disabled; enable only for local drills");
            throw new BusinessException(500, "INJECTED_ROLLBACK",
                    "Synthetic failure after flushed trade INSERT; entire transaction rolls back");
        }
        e.state = "SIMULATED_BOOKED";
        enquiries.flush(); // @Version detects a competing update
        audit(id, "TradeBooked",
                fields("tradeId", t.id, "offerId", firm.id, "termsVersion", t.termsVersion, "simulated", true));
        Outbox event = new Outbox();
        event.id = UUID.randomUUID();
        event.tradeId = t.id;
        event.schemaVersion = 1;
        event.eventType = "TradeBooked";
        event.state = "COMMITTED_NOT_PUBLISHED";
        event.createdAt = clock.instant();
        event.payload = json.write(fields("eventId", event.id, "eventType", event.eventType, "schemaVersion", 1,
                "tradeId", t.id, "product", t.product, "termsVersion", t.termsVersion, "termsHash", e.termsHash));
        outbox.save(event);
        return tradeDto(t, false);
    }

    /**
     * Called only after a competing acceptance transaction has rolled back at the
     * proxy boundary.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> recoverAcceptance(@NonNull UUID id, ApiController.AcceptRequest request) {
        return trades.findByIdempotencyKey(request.idempotencyKey())
                .filter(t -> t.enquiryId.equals(id) && t.offerId.equals(request.offerId()))
                .map(t -> tradeDto(t, false));
    }

    @Transactional
    public Map<String, Object> value(@NonNull UUID id, JsonNode body) {
        if (!body.isObject())
            throw new BusinessException(400, "INVALID_REQUEST", "Valuation body must be an object");
        Enquiry e = enquiry(id);
        ObjectNode request = body.deepCopy();
        request.set("terms", json.read(e.termsJson));
        JsonNode result = pricer.call("value", request);
        Valuation v = new Valuation();
        v.id = UUID.randomUUID();
        v.enquiryId = id;
        v.createdAt = clock.instant();
        v.requestJson = json.write(request);
        v.resultJson = json.write(result);
        valuations.save(v);
        audit(id, "ValuationRecorded", fields("valuationId", v.id, "termsVersion", e.termsVersion));
        return fields("id", v.id, "request", request, "result", result);
    }

    @Transactional
    public Map<String, Object> lifecycle(@NonNull UUID tradeId, JsonNode fixings) {
        Trade t = trade(tradeId);
        // Serialize concurrent replay for this trade using the enquiry's DB row lock.
        enquiries.findLocked(t.enquiryId).orElseThrow();
        if (!fixings.isObject())
            throw new BusinessException(400, "INVALID_FIXINGS", "fixings must map observation date to spot");
        Map<String, BigDecimal> combined = new TreeMap<>();
        for (LifecycleFixing prior : lifecycleFixings.findByTradeId(tradeId))
            combined.put(prior.observationDate.toString(), prior.spot);
        for (var entry : fixings.properties()) {
            if (!entry.getValue().isNumber())
                throw new BusinessException(400, "INVALID_FIXING", "Fixing must be numeric");
            BigDecimal spot = entry.getValue().decimalValue();
            if (combined.containsKey(entry.getKey()) && combined.get(entry.getKey()).compareTo(spot) != 0)
                throw new BusinessException(409, "FIXING_CONFLICT",
                        "An already processed historical fixing cannot change");
            combined.put(entry.getKey(), spot);
        }
        JsonNode result = pricer.call("replay",
                json.mapper.valueToTree(fields("terms", json.read(t.termsJson), "fixings", combined)));
        Set<String> savedDates = new HashSet<>();
        for (LifecycleFixing prior : lifecycleFixings.findByTradeId(tradeId))
            savedDates.add(prior.observationDate.toString());
        for (var entry : combined.entrySet())
            if (!savedDates.contains(entry.getKey())) {
                LifecycleFixing f = new LifecycleFixing();
                f.id = UUID.randomUUID();
                f.tradeId = tradeId;
                f.observationDate = LocalDate.parse(entry.getKey());
                f.spot = entry.getValue();
                lifecycleFixings.save(f);
            }
        for (JsonNode item : result.path("obligations")) {
            if (obligations.existsByTradeIdAndEventKey(tradeId, item.path("eventKey").asText()))
                continue;
            Obligation o = new Obligation();
            o.id = UUID.randomUUID();
            o.tradeId = tradeId;
            o.eventKey = item.path("eventKey").asText();
            o.observationDate = LocalDate.parse(item.path("observationDate").asText());
            o.paymentDate = LocalDate.parse(item.path("paymentDate").asText());
            o.kind = item.path("kind").asText();
            o.cash = item.path("cash").decimalValue();
            o.shares = item.path("shares").decimalValue();
            o.currency = item.path("currency").asText();
            o.state = "DETERMINED";
            obligations.save(o);
        }
        audit(t.enquiryId, "LifecycleReplayed", fields("tradeId", t.id, "result", result));
        return fields("tradeId", tradeId, "replay", result, "obligations",
                obligations.findByTradeIdOrderByPaymentDateAsc(tradeId).stream().map(this::obligationDto).toList());
    }

    @Transactional
    public Map<String, Object> advance(@NonNull UUID tradeId, LocalDate asOf, boolean settle) {
        trade(tradeId);
        List<Obligation> list = obligations.findByTradeIdOrderByPaymentDateAsc(tradeId);
        for (Obligation o : list)
            if (!o.paymentDate.isAfter(asOf) && !o.state.equals("SIMULATED_SETTLED"))
                o.state = settle ? "SIMULATED_SETTLED" : "DUE";
        return fields("tradeId", tradeId, "asOf", asOf, "obligations", list.stream().map(this::obligationDto).toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTrade(@NonNull UUID id) {
        return tradeDto(trade(id), true);
    }

    private Enquiry enquiry(@NonNull UUID id) {
        return enquiries.findById(id)
                .orElseThrow(() -> new BusinessException(404, "ENQUIRY_NOT_FOUND", "Unknown enquiry"));
    }

    private Trade trade(@NonNull UUID id) {
        return trades.findById(id).orElseThrow(() -> new BusinessException(404, "TRADE_NOT_FOUND", "Unknown trade"));
    }

    private Offer offer(@NonNull UUID id, @NonNull UUID enquiryId) {
        Offer o = offers.findById(id).orElseThrow(() -> new BusinessException(404, "OFFER_NOT_FOUND", "Unknown offer"));
        if (!o.enquiry.getId().equals(enquiryId))
            throw new BusinessException(409, "WRONG_ENQUIRY", "Offer belongs to another enquiry");
        return o;
    }

    private void requireUnbooked(Enquiry e) {
        if (e.state.equals("SIMULATED_BOOKED") || trades.findByEnquiryId(e.id).isPresent())
            throw new BusinessException(409, "ALREADY_BOOKED", "Enquiry already has a simulated trade");
    }

    private String comparability(Enquiry e, Offer o, Instant now) {
        if (!"AVAILABLE".equals(o.status))
            return o.status;
        if (!e.termsVersion.equals(o.termsVersion) || !e.termsHash.equals(o.termsHash))
            return "STALE_TERMS";
        if (!json.read(e.termsJson).path("currency").asText().equals(o.currency) || size(e).compareTo(o.size) != 0)
            return "NOT_COMPARABLE";
        if (o.expiresAt == null || !o.expiresAt.isAfter(now))
            return "EXPIRED";
        return "COMPARABLE";
    }

    private void requireComparable(Enquiry e, Offer o) {
        String status = comparability(e, o, clock.instant());
        if (!status.equals("COMPARABLE"))
            throw new BusinessException(409, status, "Offer cannot be used: " + status);
    }

    private BigDecimal size(Enquiry e) {
        JsonNode contract = json.read(e.termsJson);
        return contract.path(e.product.equals("AUTOCALLABLE") ? "notional" : "baseQuantity").decimalValue();
    }

    private BigDecimal netCost(Enquiry e, Offer o) {
        return e.product.equals("AUTOCALLABLE") ? size(e).multiply(o.price).divide(new BigDecimal("100")).add(o.fee)
                : o.fee;
    }

    private Offer fromQuote(Enquiry e, UUID requestId, String issuer, IssuerGateway.Quote q, String kind) {
        Offer o = new Offer();
        o.id = UUID.randomUUID();
        o.enquiry = e;
        o.requestId = requestId;
        o.issuer = issuer;
        o.kind = kind;
        o.status = q.status();
        o.termsVersion = q.termsVersion();
        o.termsHash = q.termsHash();
        o.termsJson = q.termsJson();
        o.currency = q.currency();
        o.size = size(e);
        o.price = q.price();
        o.fee = q.fee();
        o.priceConvention = q.convention();
        o.creditInformation = "SYNTHETIC issuer; credit risk not modelled or rated";
        o.receivedAt = clock.instant();
        o.expiresAt = q.expiresAt();
        o.details = q.details();
        return o;
    }

    private Offer failedOffer(Enquiry e, UUID requestId, String issuer, String status, String message) {
        return fromQuote(e, requestId, issuer,
                new IssuerGateway.Quote(status, e.termsVersion, e.termsHash, e.termsJson,
                        json.read(e.termsJson).path("currency").asText(), BigDecimal.ZERO, BigDecimal.ZERO, "NO_OFFER",
                        clock.instant(), message),
                "INDICATIVE");
    }

    private Offer copyOffer(Enquiry e, Offer source, String kind) {
        return fromQuote(e, UUID.randomUUID(), source.issuer,
                new IssuerGateway.Quote(source.status, source.termsVersion, source.termsHash, source.termsJson,
                        source.currency, source.price, source.fee, source.priceConvention,
                        clock.instant().plusSeconds(600), source.details),
                kind);
    }

    private void audit(UUID id, String type, Object payload) {
        AuditEvent event = new AuditEvent();
        event.id = UUID.randomUUID();
        event.aggregateId = id;
        event.eventType = type;
        event.correlationId = CorrelationFilter.current();
        event.payload = json.write(payload);
        event.occurredAt = clock.instant();
        audits.save(event);
    }

    private Map<String, Object> enquiryDto(Enquiry e) {
        return fields("id", e.id, "version", e.version, "product", e.product, "termsVersion", e.termsVersion, "terms",
                json.read(e.termsJson), "termsHash", e.termsHash, "state", e.state, "clientId", e.clientId,
                "clientReference", e.clientReference, "operatorId", e.operatorId, "eligibility", e.eligibility,
                "selectedOfferId", e.selectedOfferId, "selectionRationale", e.selectionRationale, "instruction",
                e.instructionJson == null ? null : json.read(e.instructionJson), "createdAt", e.createdAt);
    }

    private Map<String, Object> offerDto(Offer o) {
        return fields("id", o.id, "requestId", o.requestId, "sourceOfferId", o.sourceOfferId, "issuer", o.issuer,
                "kind", o.kind, "status", o.status, "termsVersion", o.termsVersion, "termsHash", o.termsHash, "terms",
                json.read(o.termsJson), "currency", o.currency, "size", o.size, "price", o.price, "fee", o.fee,
                "priceConvention", o.priceConvention, "creditInformation", o.creditInformation, "receivedAt",
                o.receivedAt, "expiresAt", o.expiresAt, "details", o.details);
    }

    private Map<String, Object> obligationDto(Obligation o) {
        return fields("id", o.id, "eventKey", o.eventKey, "observationDate", o.observationDate, "paymentDate",
                o.paymentDate, "kind", o.kind, "cash", o.cash, "shares", o.shares, "currency", o.currency, "state",
                o.state);
    }

    private Map<String, Object> auditDto(AuditEvent a) {
        return fields("id", a.id, "eventType", a.eventType, "correlationId", a.correlationId, "occurredAt",
                a.occurredAt, "payload", json.read(a.payload));
    }

    private Map<String, Object> tradeDto(Trade t, boolean details) {
        Map<String, Object> result = fields("id", t.id, "enquiryId", t.enquiryId, "offerId", t.offerId,
                "idempotencyKey", t.idempotencyKey, "product", t.product, "termsVersion", t.termsVersion, "terms",
                json.read(t.termsJson), "state", t.state, "originalPurchasePrice", t.originalPurchasePrice,
                "maximumPurchaseExposure", t.maximumPurchaseExposure, "bookedAt", t.bookedAt, "simulated", true);
        if (details) {
            result.put("obligations",
                    obligations.findByTradeIdOrderByPaymentDateAsc(t.id).stream().map(this::obligationDto).toList());
            result.put("audit", audits.findByAggregateId(t.enquiryId).stream().map(this::auditDto).toList());
            result.put(
                    "outbox", outbox
                            .findByTradeId(t.id).stream().map(o -> fields("id", o.id, "schemaVersion", o.schemaVersion,
                                    "eventType", o.eventType, "state", o.state, "payload", json.read(o.payload)))
                            .toList());
        }
        return result;
    }
}
