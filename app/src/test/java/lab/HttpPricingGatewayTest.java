package lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real loopback HTTP calls to a controlled stub: this checks the adapter, not
 * the pricing model.
 */
class HttpPricingGatewayTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null)
            server.stop(0);
    }

    private HttpPricingGateway gateway(int status, long delay, long timeout) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/value", exchange -> {
            try {
                Thread.sleep(delay);
                byte[] body = (status == 200 ? "{\"npv\":42}" : "{\"error\":\"CONTROLLED_FAILURE\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return new HttpPricingGateway(new JsonSupport(new ObjectMapper()),
                "http://127.0.0.1:" + server.getAddress().getPort(), timeout);
    }

    @Test
    void roundTripsSuccessfulJsonOverHttp() throws Exception {
        assertEquals(42,
                gateway(200, 0, 2000).call("value", new ObjectMapper().createObjectNode()).path("npv").asInt());
    }

    @Test
    void mapsActualHttpError() throws Exception {
        var gateway = gateway(500, 0, 2000);
        var error = assertThrows(BusinessException.class,
                () -> gateway.call("value", new ObjectMapper().createObjectNode()));
        assertEquals(502, error.status);
        assertEquals("PRICER_REJECTED", error.code);
    }

    @Test
    void boundsActualHttpWait() throws Exception {
        var gateway = gateway(200, 500, 50);
        var error = assertThrows(BusinessException.class,
                () -> gateway.call("value", new ObjectMapper().createObjectNode()));
        assertEquals(504, error.status);
        assertEquals("PRICER_TIMEOUT", error.code);
    }
}
