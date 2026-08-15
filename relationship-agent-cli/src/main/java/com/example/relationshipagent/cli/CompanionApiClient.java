package com.example.relationshipagent.cli;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Companion REST API 的轻量客户端；不依赖 Spring，也不接触数据库或模型配置。 */
public final class CompanionApiClient {
    private final URI server;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration requestTimeout;
    private final Duration pollInterval;

    public CompanionApiClient(String server, ObjectMapper json, Duration requestTimeout) {
        this(server, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json,
                requestTimeout, Duration.ofMillis(500));
    }

    CompanionApiClient(String server, HttpClient http, ObjectMapper json,
                       Duration requestTimeout, Duration pollInterval) {
        String normalized = Objects.requireNonNull(server, "server").trim().replaceAll("/+$", "");
        this.server = URI.create(normalized);
        this.http = http;
        this.json = json;
        this.requestTimeout = requestTimeout;
        this.pollInterval = pollInterval;
    }

    public void checkAvailable() {
        // 会话列表接口同时验证网络、chatFileId 和 Companion API 契约，因此无需额外健康检查端点。
        HttpRequest request = request("/api/chat-files/__cli_probe__/companion/sessions?size=1").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 500) {
                throw new ApiException("后端暂时不可用（HTTP " + response.statusCode() + "）");
            }
        } catch (IOException e) {
            throw networkFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("连接检查被中断", e);
        }
    }

    public ApiModels.SessionInfo createSession(String chatFileId, String targetPerson) {
        return call("POST", base(chatFileId) + "/sessions", Map.of("targetPerson", targetPerson),
                ApiModels.SessionInfo.class).data();
    }

    public List<ApiModels.SessionInfo> listSessions(String chatFileId, String targetPerson, String status, int size) {
        StringBuilder path = new StringBuilder(base(chatFileId)).append("/sessions?size=").append(Math.max(1, Math.min(100, size)));
        if (targetPerson != null && !targetPerson.isBlank()) path.append("&targetPerson=").append(encode(targetPerson));
        if (status != null && !status.isBlank()) path.append("&status=").append(encode(status));
        JavaType type = json.getTypeFactory().constructCollectionType(List.class, ApiModels.SessionInfo.class);
        return this.<List<ApiModels.SessionInfo>>call("GET", path.toString(), null, type).data();
    }

    public ApiModels.SessionInfo getSession(String chatFileId, String sessionId) {
        return call("GET", base(chatFileId) + "/sessions/" + encodePath(sessionId), null,
                ApiModels.SessionInfo.class).data();
    }

    public List<ApiModels.MessageInfo> messages(String chatFileId, String sessionId, int size) {
        JavaType type = json.getTypeFactory().constructCollectionType(List.class, ApiModels.MessageInfo.class);
        return this.<List<ApiModels.MessageInfo>>call("GET", base(chatFileId) + "/sessions/" + encodePath(sessionId)
                + "/messages?size=" + Math.max(1, Math.min(100, size)), null, type).data();
    }

    public ApiModels.Exchange send(String chatFileId, String sessionId, String clientRequestId, String content) {
        long started = System.nanoTime();
        ApiResult<ApiModels.TurnInfo> initial = call("POST", base(chatFileId) + "/sessions/" + encodePath(sessionId)
                        + "/messages", Map.of("clientRequestId", clientRequestId, "content", content),
                ApiModels.TurnInfo.class);
        ApiModels.TurnInfo turn = initial.data();
        if (initial.httpStatus() == 202 || turn.inProgress()) {
            turn = waitForTurn(chatFileId, sessionId, clientRequestId, content, turn);
        }
        return new ApiModels.Exchange(turn, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private ApiModels.TurnInfo waitForTurn(String chatFileId, String sessionId, String clientRequestId,
                                           String content, ApiModels.TurnInfo initial) {
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            sleepPollInterval();
            ApiModels.TurnStatus status = call("GET", base(chatFileId) + "/sessions/" + encodePath(sessionId)
                    + "/turns/" + encodePath(initial.turnId()), null, ApiModels.TurnStatus.class).data();
            if ("FAILED".equals(status.status())) {
                throw new ApiException("本轮生成失败，请使用同一条消息重试");
            }
            if ("SUCCESS".equals(status.status())) {
                // 使用同一幂等键重放请求，后端只回读已有结果，不会再次调用模型；这样还能拿到引用审计摘要。
                return call("POST", base(chatFileId) + "/sessions/" + encodePath(sessionId) + "/messages",
                        Map.of("clientRequestId", clientRequestId, "content", content), ApiModels.TurnInfo.class).data();
            }
        }
        throw new ApiException("等待回复超时（" + requestTimeout.toSeconds() + " 秒）；本轮可能仍在后端执行，可稍后用 /history 查看");
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("等待回复被中断", e);
        }
    }

    private <T> ApiResult<T> call(String method, String path, Object body, Class<T> type) {
        return call(method, path, body, json.getTypeFactory().constructType(type));
    }

    private <T> ApiResult<T> call(String method, String path, Object body, JavaType type) {
        try {
            HttpRequest.Builder builder = request(path);
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json; charset=UTF-8");
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode envelope;
            try {
                envelope = json.readTree(response.body());
            } catch (Exception invalidJson) {
                throw new ApiException("后端返回了无法解析的响应（HTTP " + response.statusCode() + "）");
            }
            int code = envelope.path("code").asInt(Integer.MIN_VALUE);
            String message = envelope.path("message").asText("请求失败");
            if (response.statusCode() < 200 || response.statusCode() >= 300 || code != 0) {
                throw new ApiException(message + "（HTTP " + response.statusCode() + ", code " + code + "）");
            }
            T data = json.convertValue(envelope.path("data"), type);
            return new ApiResult<>(response.statusCode(), data);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw networkFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("请求被中断", e);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(server.resolve(path)).timeout(requestTimeout)
                .header("Accept", "application/json");
    }

    private String base(String chatFileId) {
        return "/api/chat-files/" + encodePath(chatFileId) + "/companion";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private static ApiException networkFailure(IOException e) {
        String message = e instanceof ConnectException ? "无法连接后端，请确认 Spring Boot 已启动"
                : "后端网络请求失败：" + e.getClass().getSimpleName();
        return new ApiException(message, e);
    }

    record ApiResult<T>(int httpStatus, T data) {
    }

    public static final class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }

        public boolean isSessionEnded() {
            return getMessage() != null && (getMessage().contains("4073") || getMessage().contains("模拟会话已结束或过期"));
        }
    }
}
