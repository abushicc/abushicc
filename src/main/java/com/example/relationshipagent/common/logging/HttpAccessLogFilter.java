package com.example.relationshipagent.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 输出不含请求参数和正文的 HTTP 访问日志。
 *
 * <p>聊天正文、模型响应和密钥都不能进入日志；URI 只包含资源 ID。CLI 对 turn 状态的高频轮询
 * 降为 DEBUG，避免模型生成期间刷屏。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpAccessLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(HttpAccessLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (isTurnPolling(request)) {
                log.debug("HTTP {} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), elapsedMs);
            } else {
                log.info("HTTP {} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), elapsedMs);
            }
        }
    }

    private static boolean isTurnPolling(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().contains("/turns/");
    }
}
