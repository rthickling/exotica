package lab;

import com.fasterxml.jackson.databind.JsonNode;

public interface PricingGateway {
    JsonNode call(String operation, JsonNode request);
}
