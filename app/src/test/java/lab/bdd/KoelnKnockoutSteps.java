package lab.bdd;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.cucumber.java.ParameterType;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KoelnKnockoutSteps {
	private BigDecimal denomination;
	private Currency currency;
	private BigDecimal couponRate;
	private BigDecimal knockOutLevel;
	private LocalDate firstKnockOutObservationDate;
	private LocalDate associatedPaymentDate;
	/** Contract assembled once Background is complete (stand-in for a booked trade). */
	private Map<String, Object> terms;
	/** Pricer replay result after the When fixing is applied. */
	private Map<String, Object> replay;
	private BigDecimal closingPrice;
	private LocalDate observationDate;
	private LocalDate paymentDate;

	/** Enables {date} in step expressions (ISO-8601 local dates like 2027-01-15). */
	@ParameterType("\\d{4}-\\d{2}-\\d{2}")
	public LocalDate date(String text) {
		return LocalDate.parse(text);
	}

	@Given("a booked KOELN with denomination {word} {bigdecimal}")
	public void aBookedKoelnWithDenomination(String currencyCode, BigDecimal denomination) {
		// Background is still incomplete (barrier, schedule, coupons). Only remember
		// size/currency here; we book once the remaining Givens have filled the terms.
		this.currency = Currency.getInstance(currencyCode);
		this.denomination = denomination;
	}

	@Given("the note has no periodic coupons")
	public void theNoteHasNoPeriodicCoupons() {
		// Encode "no coupons" as a zero coupon rate on the terms we will book later.
		// The feature is ruling out COUPON obligations so knock-out/redemption stays isolated.
		this.couponRate = BigDecimal.ZERO;
	}

	@Given("its knock-out level is {bigdecimal}")
	public void itsKnockOutLevelIs(BigDecimal knockOutLevel) {
		// Barrier for this story: spot >= knockOutLevel knocks the note out.
		// Stored for the terms we book later; the When/Then assert against this level.
		this.knockOutLevel = knockOutLevel;
	}

	@Given("its first knock-out observation date is {date}")
	public void itsFirstKnockOutObservationDateIs(LocalDate firstKnockOutObservationDate) {
		// Schedule: the first (and, for this feature, only) date on which KO can be tested.
		// Later the When records a closing price for this same calendar date.
		this.firstKnockOutObservationDate = firstKnockOutObservationDate;
	}

	@Given("the associated payment date is {date}")
	public void theAssociatedPaymentDateIs(LocalDate associatedPaymentDate) {
		// Last Background fact: if KO fires, redemption cash is due on this date.
		this.associatedPaymentDate = associatedPaymentDate;
		// All arrange data is present — build the contract now (see bookKoeln).
		this.terms = bookKoeln();
	}

	/**
	 * Turn Background fields into note-shaped terms the pricer can replay.
	 * KOELN "knock-out level" is modelled as autocallBarrier with firstCallIndex 0
	 * and couponRate 0 so a print at/above the barrier redeems notional and terminates.
	 * A full WorkflowService accept() booking can replace this later; the feature only
	 * needs a booked economic contract before the When.
	 */
	private Map<String, Object> bookKoeln() {
		Map<String, Object> contract = new LinkedHashMap<>();
		contract.put("product", "AUTOCALLABLE");
		contract.put("termsVersion", "koeln-bdd");
		contract.put("currency", currency.getCurrencyCode());
		contract.put("underlying", "SYNTH_EQ");
		contract.put("initialReference", new BigDecimal("100"));
		contract.put("startDate", firstKnockOutObservationDate.minusDays(1).toString());
		// A second, later pair is required: on a note, the final observation always
		// redeems. Without a later maturity date, a print below the KO level would still
		// terminate with redemption and break the "no KO" scenario.
		LocalDate maturityObservation = firstKnockOutObservationDate.plusMonths(3);
		LocalDate maturityPayment = associatedPaymentDate.plusMonths(3);
		contract.put("observationDates",
				List.of(firstKnockOutObservationDate.toString(), maturityObservation.toString()));
		contract.put("paymentDates", List.of(associatedPaymentDate.toString(), maturityPayment.toString()));
		contract.put("notional", denomination);
		contract.put("couponRate", couponRate);
		// couponBarrier must be > 0 for validation; rate 0 already means no coupon cash,
		// and a huge barrier means the coupon branch never fires on our spots.
		contract.put("couponBarrier", new BigDecimal("1000000000"));
		contract.put("autocallBarrier", knockOutLevel);
		contract.put("firstCallIndex", 0);
		contract.put("capitalBarrier", new BigDecimal("0.000001"));
		return contract;
	}

	@When("a closing price of {bigdecimal} is recorded for {date}")
	public void aClosingPriceIsRecordedForObservationDate(BigDecimal closingPrice, LocalDate observationDate)
			throws Exception {
		// Act: apply one fixing to the arranged contract (same idea as workflow.lifecycle → replay).
		this.closingPrice = closingPrice;
		this.observationDate = observationDate;
		Map<String, Object> fixings = Map.of(observationDate.toString(), closingPrice);
		Map<String, Object> request = Map.of("terms", terms, "fixings", fixings);
		this.replay = callReplay(request);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> callReplay(Map<String, Object> request) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String base = System.getenv().getOrDefault("PRICER_URL", "http://127.0.0.1:8090");
		HttpRequest http = HttpRequest.newBuilder(URI.create(base + "/replay"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
				.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(http, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException(
					"pricer /replay failed: HTTP " + response.statusCode() + " " + response.body());
		}
		return mapper.readValue(response.body(), Map.class);
	}

	@Then("exactly one cash redemption obligation of {word} {bigdecimal} is scheduled for {date}")
	public void exactlyOneCashRedemptionObligationIsScheduled(String currencyCode, BigDecimal expectedCash,
			LocalDate paymentDate) {
		this.paymentDate = paymentDate;
		List<Map<String, Object>> redemptions = redemptions();
		assertEquals(1, redemptions.size(), "expected exactly one REDEMPTION obligation");
		Map<String, Object> redemption = redemptions.getFirst();
		assertEquals(currencyCode, redemption.get("currency"));
		assertEquals(0, cashAmount(redemption).compareTo(expectedCash),
				() -> "redemption cash " + redemption.get("cash") + " != " + expectedCash);
		assertEquals(paymentDate.toString(), redemption.get("paymentDate"));
	}

	@Then("the note terminates on {date}")
	public void theNoteTerminatesOn(LocalDate terminationDate) {
		assertEquals(Boolean.TRUE, replay.get("terminated"));
		assertEquals(terminationDate.toString(), replay.get("terminationDate"));
	}

	@Then("no redemption obligation is created")
	public void noRedemptionObligationIsCreated() {
		assertTrue(redemptions().isEmpty(), "expected no REDEMPTION obligations");
	}

	@Then("the note has not terminated")
	public void theNoteHasNotTerminated() {
		assertFalse(Boolean.TRUE.equals(replay.get("terminated")));
		assertNull(replay.get("terminationDate"));
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> obligations() {
		return (List<Map<String, Object>>) replay.get("obligations");
	}

	private List<Map<String, Object>> redemptions() {
		return obligations().stream().filter(o -> "REDEMPTION".equals(o.get("kind"))).toList();
	}

	private static BigDecimal cashAmount(Map<String, Object> obligation) {
		return new BigDecimal(String.valueOf(obligation.get("cash")));
	}
}

