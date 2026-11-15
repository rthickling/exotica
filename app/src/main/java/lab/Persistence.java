package lab;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Entity
@Table(name = "term_version")
class TermVersion {
    @Id
    String id;
    @Column(nullable = false, columnDefinition = "text", updatable = false)
    String termsJson;
    @Column(nullable = false, updatable = false)
    String termsHash;

    protected TermVersion() {
    }
}

@Entity
@Table(name = "enquiry")
class Enquiry {
    @Id
    UUID id;
    @Version
    long version;
    String product;
    String termsVersion;
    String state;
    String clientId;
    String operatorId;
    String eligibility;
    @Column(columnDefinition = "text")
    String termsJson;
    String termsHash;
    UUID selectedOfferId;
    @Column(columnDefinition = "text")
    String selectionRationale;
    @Column(columnDefinition = "text")
    String instructionJson;
    Instant createdAt;
    @OneToMany(mappedBy = "enquiry", fetch = FetchType.LAZY)
    List<Offer> offers = new ArrayList<>();

    protected Enquiry() {
    }

    public UUID getId() {
        return id;
    }
}

@Entity
@Table(name = "offer")
class Offer {
    @Id
    UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enquiry_id")
    Enquiry enquiry;
    UUID requestId;
    UUID sourceOfferId;
    String issuer;
    String kind;
    String status;
    String termsVersion;
    String termsHash;
    String currency;
    BigDecimal size;
    BigDecimal price;
    BigDecimal fee;
    String priceConvention;
    String creditInformation;
    @Column(columnDefinition = "text")
    String termsJson;
    @Column(columnDefinition = "text")
    String details;
    Instant receivedAt;
    Instant expiresAt;

    protected Offer() {
    }
}

@Entity
@Table(name = "trade")
class Trade {
    @Id
    UUID id;
    @Column(nullable = false, unique = true)
    UUID enquiryId;
    @Column(nullable = false, unique = true)
    String idempotencyKey;
    UUID offerId;
    String product;
    String termsVersion;
    String state;
    @Column(columnDefinition = "text")
    String termsJson;
    BigDecimal originalPurchasePrice;
    BigDecimal maximumPurchaseExposure;
    Instant bookedAt;

    protected Trade() {
    }
}

@Entity
@Table(name = "audit_event")
class AuditEvent {
    @Id
    UUID id;
    UUID aggregateId;
    String eventType;
    String correlationId;
    @Column(columnDefinition = "text")
    String payload;
    Instant occurredAt;

    protected AuditEvent() {
    }
}

@Entity
@Table(name = "outbox")
class Outbox {
    @Id
    UUID id;
    UUID tradeId;
    int schemaVersion;
    String eventType;
    String state;
    @Column(columnDefinition = "text")
    String payload;
    Instant createdAt;

    protected Outbox() {
    }
}

@Entity
@Table(name = "obligation", uniqueConstraints = @UniqueConstraint(columnNames = { "trade_id", "event_key" }))
class Obligation {
    @Id
    UUID id;
    @Column(name = "trade_id")
    UUID tradeId;
    @Column(name = "event_key")
    String eventKey;
    LocalDate observationDate;
    LocalDate paymentDate;
    String kind;
    BigDecimal cash;
    BigDecimal shares;
    String currency;
    String state;

    protected Obligation() {
    }
}

@Entity
@Table(name = "valuation")
class Valuation {
    @Id
    UUID id;
    UUID enquiryId;
    Instant createdAt;
    @Column(columnDefinition = "text")
    String requestJson;
    @Column(columnDefinition = "text")
    String resultJson;

    protected Valuation() {
    }
}

@Entity
@Table(name = "lifecycle_fixing", uniqueConstraints = @UniqueConstraint(columnNames = { "trade_id",
        "observation_date" }))
class LifecycleFixing {
    @Id
    UUID id;
    @Column(name = "trade_id")
    UUID tradeId;
    @Column(name = "observation_date")
    LocalDate observationDate;
    BigDecimal spot;

    protected LifecycleFixing() {
    }
}

interface TermRepository extends JpaRepository<TermVersion, String> {
}

interface EnquiryRepository extends JpaRepository<Enquiry, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Enquiry e where e.id=:id")
    Optional<Enquiry> findLocked(@Param("id") UUID id);
}

interface OfferRepository extends JpaRepository<Offer, UUID> {
    List<Offer> findByEnquiryIdOrderByReceivedAtAsc(UUID enquiryId);
}

interface TradeRepository extends JpaRepository<Trade, UUID> {
    Optional<Trade> findByIdempotencyKey(String key);

    Optional<Trade> findByEnquiryId(UUID enquiryId);
}

interface AuditRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findByAggregateId(UUID aggregateId);
}

interface OutboxRepository extends JpaRepository<Outbox, UUID> {
    List<Outbox> findByTradeId(UUID tradeId);
}

interface ObligationRepository extends JpaRepository<Obligation, UUID> {
    List<Obligation> findByTradeIdOrderByPaymentDateAsc(UUID tradeId);

    boolean existsByTradeIdAndEventKey(UUID tradeId, String eventKey);
}

interface ValuationRepository extends JpaRepository<Valuation, UUID> {
    List<Valuation> findByEnquiryId(UUID enquiryId);
}

interface LifecycleFixingRepository extends JpaRepository<LifecycleFixing, UUID> {
    List<LifecycleFixing> findByTradeId(UUID tradeId);
}
