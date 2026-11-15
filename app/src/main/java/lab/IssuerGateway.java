package lab;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Replaceable boundary. Every offer produced by this implementation is
 * synthetic.
 */
public interface IssuerGateway {
    Quote quote(QuoteRequest request) throws Exception;

    record QuoteRequest(UUID requestId, String issuer, String product, String termsVersion, String termsHash,
            String termsJson, String currency, BigDecimal size, String scenario, Instant now) {
    }

    record Quote(String status, String termsVersion, String termsHash, String termsJson, String currency,
            BigDecimal price, BigDecimal fee, String convention, Instant expiresAt, String details) {
    }
}

@Component
class SimulatedIssuerGateway implements IssuerGateway {
    @Override
    public Quote quote(QuoteRequest r) throws Exception {
        if ("TIMEOUT".equals(r.scenario()))
            Thread.sleep(1500);
        if ("ERROR".equals(r.scenario()))
            throw new IllegalStateException("Synthetic issuer service failure");
        if (!Set.of("OK", "TIMEOUT", "EXPIRED", "STALE", "INCOMPARABLE", "DECLINED", "UNSUPPORTED")
                .contains(r.scenario()))
            throw new BusinessException(400, "INVALID_SCENARIO", "Unsupported scenario " + r.scenario());
        String status = switch (r.scenario()) {
            case "DECLINED" -> "DECLINED";
            case "UNSUPPORTED" -> "UNSUPPORTED";
            default -> "AVAILABLE";
        };
        boolean note = r.product().equals("AUTOCALLABLE");
        int index = List.of("Issuer-A", "Issuer-B", "Issuer-C").indexOf(r.issuer());
        BigDecimal price = note ? new BigDecimal(index == 0 ? "100" : index == 1 ? "99.8" : "100.2") : BigDecimal.ZERO;
        BigDecimal fee = note ? new BigDecimal("100") : new BigDecimal(index == 0 ? "0" : index == 1 ? "-20" : "20");
        return new Quote(status,
                "STALE".equals(r.scenario()) ? r.termsVersion() + "-old" : r.termsVersion(),
                "STALE".equals(r.scenario()) ? "stale-hash" : r.termsHash(), r.termsJson(),
                "INCOMPARABLE".equals(r.scenario()) ? "USD" : r.currency(), price, fee,
                note ? "ISSUE_PRICE_PERCENT_PLUS_HKD_FEE" : "UPFRONT_HKD_FEE_FIXED_STRIKE",
                r.now().plusSeconds("EXPIRED".equals(r.scenario()) ? -1 : 600),
                "SIMULATED: fixed payoff terms; no executable market price or real issuer credit assessment");
    }
}
