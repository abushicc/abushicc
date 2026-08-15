package com.example.relationshipagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompanionApiClientTest {
    private HttpServer server;
    private CompanionApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        client = new CompanionApiClient("http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newHttpClient(), json, Duration.ofSeconds(2), Duration.ofMillis(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesSessionAndEncodesChineseTarget() {
        server.createContext("/api/chat-files/file/companion/sessions", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            assertTrue(query.contains("targetPerson=%E7%8C%95%E7%8C%B4"));
            assertTrue(query.contains("status=ACTIVE"));
            assertTrue(query.contains("size=1"));
            respond(exchange, 200, ok("[{\"id\":\"s1\",\"targetPerson\":\"猕猴\",\"status\":\"ACTIVE\"}]"));
        });

        var sessions = client.listSessions("file", "猕猴", "ACTIVE", 1);

        assertEquals(1, sessions.size());
        assertEquals("s1", sessions.get(0).id());
    }

    @Test
    void reportsBusinessFailureEvenWhenHttpIsSuccessful() {
        server.createContext("/api/chat-files/file/companion/sessions/s1", exchange ->
                respond(exchange, 200, "{\"code\":5404,\"message\":\"会话不存在\"}"));

        CompanionApiClient.ApiException error = assertThrows(CompanionApiClient.ApiException.class,
                () -> client.getSession("file", "s1"));

        assertTrue(error.getMessage().contains("会话不存在"));
        assertTrue(error.getMessage().contains("5404"));
    }

    @Test
    void identifiesExpiredSessionCode() {
        assertTrue(new CompanionApiClient.ApiException("模拟会话已结束或过期（HTTP 409, code 4073）").isSessionEnded());
        assertFalse(new CompanionApiClient.ApiException("Persona 不可用（HTTP 409, code 4072）").isSessionEnded());
    }

    @Test
    void pollsAcceptedTurnAndReplaysSameIdempotencyKey() {
        AtomicInteger sends = new AtomicInteger();
        String messagePath = "/api/chat-files/file/companion/sessions/s1/messages";
        server.createContext(messagePath, exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"clientRequestId\":\"request-1\""));
            assertTrue(body.contains("你好"));
            if (sends.incrementAndGet() == 1) {
                respond(exchange, 202, ok("{\"turnId\":\"t1\",\"userMessageId\":\"u1\",\"status\":\"RUNNING\",\"inProgress\":true}"));
            } else {
                respond(exchange, 200, ok("{\"turnId\":\"t1\",\"userMessageId\":\"u1\",\"assistantMessage\":{\"id\":\"a1\",\"role\":\"ASSISTANT\",\"content\":\"在呢\"},\"status\":\"SUCCESS\",\"inProgress\":false,\"usedMemoryIds\":[\"m1\"],\"usedSessionIds\":[],\"usedChunkIds\":[\"c1\"],\"retrievalDecision\":\"SEARCH\",\"historyStance\":\"GROUNDED\"}"));
            }
        });
        server.createContext("/api/chat-files/file/companion/sessions/s1/turns/t1", exchange ->
                respond(exchange, 200, ok("{\"turnId\":\"t1\",\"status\":\"SUCCESS\",\"assistantMessageId\":\"a1\"}")));

        ApiModels.Exchange exchange = client.send("file", "s1", "request-1", "你好");

        assertEquals(2, sends.get());
        assertEquals("在呢", exchange.turn().assistantMessage().content());
        assertEquals("SEARCH", exchange.turn().retrievalDecision());
        assertEquals(1, exchange.turn().usedMemoryIds().size());
    }

    private static String ok(String data) {
        return "{\"code\":0,\"message\":\"ok\",\"data\":" + data + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
