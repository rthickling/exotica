package lab;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Typed teaching views of the two versioned contracts; wire JSON is preserved
 * in full.
 */
public sealed interface ProductTerms permits ProductTerms.Note, ProductTerms.Accumulator {
    String termsVersion();

    String currency();

    record Note(String product, String termsVersion, String currency, String underlying,
            BigDecimal initialReference, LocalDate startDate, List<LocalDate> observationDates,
            List<LocalDate> paymentDates, BigDecimal notional, BigDecimal couponRate,
            BigDecimal couponBarrier, BigDecimal autocallBarrier, int firstCallIndex,
            BigDecimal capitalBarrier) implements ProductTerms {
    }

    record Accumulator(String product, String termsVersion, String currency, String underlying,
            BigDecimal initialReference, LocalDate startDate, List<LocalDate> observationDates,
            List<LocalDate> paymentDates, BigDecimal strike, BigDecimal baseQuantity,
            int multiplier, BigDecimal knockoutBarrier, int guaranteedObservations,
            boolean knockoutAccrues) implements ProductTerms {
    }
}
