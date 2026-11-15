package lab;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

@Component
class HttpPricingGateway implements PricingGateway {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonSupport json;
    private final String baseUrl;
    private final Duration timeout;

    HttpPricingGateway(
            JsonSupport json,
            @Value("${lab.pricer-url}") String baseUrl,
            @Value("${lab.pricer-timeout-ms}") long timeoutMs) {
        this.json = json;
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public JsonNode call(String operation, JsonNode request) {
        try {
            var http = HttpRequest.newBuilder(URI.create(baseUrl + "/" + operation)).timeout(timeout)
                    .header("Content-Type", "application/json").header("X-Correlation-ID", CorrelationFilter.current())
                    .POST(HttpRequest.BodyPublishers.ofString(json.write(request))).build();
            var response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new BusinessException(502, "PRICER_REJECTED",
                        "Pricer HTTP " + response.statusCode() + ": " + response.body());
            return json.read(response.body());
        } catch (HttpTimeoutException e) {
            throw new BusinessException(504, "PRICER_TIMEOUT", "Valuation service deadline exceeded");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "PRICER_INTERRUPTED", "Pricing interrupted");
        } catch (java.io.IOException e) {
            throw new BusinessException(502, "PRICER_UNAVAILABLE", "Cannot reach local valuation service");
        }
    }
}
