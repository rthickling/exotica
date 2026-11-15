package lab;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
class JsonSupport {
    final ObjectMapper mapper;

    JsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode internal value", e);
        }
    }

    JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decode stored value", e);
        }
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> fields(Object... args) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2)
            result.put((String) args[i], args[i + 1]);
        return result;
    }
}

@Component
class ProductCatalog {
    private final Map<String, JsonNode> products;

    ProductCatalog(JsonSupport json, @Value("${lab.terms-directory}") String directory) {
        try {
            Map<String, JsonNode> result = new LinkedHashMap<>();
            for (String version : List.of("note-v1", "acc-v1")) {
                JsonNode node = json.mapper.readTree(Files.readString(Path.of(directory, version + ".json")));
                Class<? extends ProductTerms> type = node.path("product").asText().equals("AUTOCALLABLE")
                        ? ProductTerms.Note.class
                        : ProductTerms.Accumulator.class;
                json.mapper.treeToValue(node, type); // fails on a schema mismatch instead of silently discarding terms
                result.put(node.path("product").asText(), node);
            }
            products = Collections.unmodifiableMap(result);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load frozen terms from " + directory, e);
        }
    }

    JsonNode terms(String product) {
        JsonNode node = products.get(product);
        if (node == null)
            throw new BusinessException(400, "UNSUPPORTED_PRODUCT", "Choose AUTOCALLABLE or ACCUMULATOR");
        return node.deepCopy();
    }

    Map<String, JsonNode> all() {
        return products;
    }
}

class BusinessException extends RuntimeException {
    final int status;
    final String code;

    BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
