package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tickefy.order.modules.order.client.CreatePaymentCommand;
import com.tickefy.order.modules.order.client.RealPaymentClient;
import com.tickefy.order.modules.order.client.RefundRequest;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies RealPaymentClient sends the X-Internal-Token header on BOTH createTransaction and refund
 * (F-new6). Uses a real loopback HttpServer (the existing MockRestServiceServer test reflection-replaces
 * the internal RestClient and would bypass the constructor's defaultHeader — so we exercise the real
 * client builder against a captured request instead).
 */
@Tag("unit")
class RealPaymentClientInternalTokenUnitTest {

    private static final String TOKEN = "internal-token-abcdef0123456789abcdef0123456789";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> capturedHeader = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/payments", exchange -> {
            capturedHeader.put("create", String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Token")));
            byte[] body = "{\"success\":true,\"data\":{\"paymentId\":\"P1\",\"paymentUrl\":\"http://x/p\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.createContext("/internal/payments/refund", exchange -> {
            capturedHeader.put("refund", String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Token")));
            byte[] body = ("{\"success\":true,\"data\":{\"status\":\"REFUNDED\",\"refundGatewayRef\":\"GW-1\",\"paymentTransactionId\":\""
                    + UUID.randomUUID() + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private RealPaymentClient client() {
        return new RealPaymentClient(
                baseUrl, "/internal/payments", "/internal/payments/refund",
                Duration.ofSeconds(2), Duration.ofSeconds(5), TOKEN, MAPPER);
    }

    @Test
    void createTransaction_sendsInternalTokenHeader() {
        client().createTransaction(
                new CreatePaymentCommand(ORDER_ID, USER_ID, 150000L, "VND", "order-" + ORDER_ID),
                "bearer-xyz");
        assertThat(capturedHeader.get("create")).isEqualTo(TOKEN);
    }

    @Test
    void refund_sendsInternalTokenHeader() {
        client().refund(new RefundRequest(ORDER_ID, "refund-" + ORDER_ID, 150000L));
        assertThat(capturedHeader.get("refund")).isEqualTo(TOKEN);
    }
}
