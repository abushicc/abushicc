package com.example.relationshipagent.rag;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Embedding 专用单线程执行器（设计文档 17.8 + 本手册 M3.3）。
 *
 * <p>EMBED 是长任务（云端 API 调用逐批处理），单线程池避免占满 2 线程的通用池阻塞其他阶段。
 * 线程名 embed-job-worker，CallerRunsPolicy。
 */
@Component
public class EmbeddingJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingJobExecutor.class);

    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public EmbeddingJobExecutor() {
        this.executor = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.executor.setThreadFactory(r -> {
            Thread t = new Thread(r, "embed-job-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void submit(String jobId, Runnable task) {
        if (runningTasks.containsKey(jobId)) {
            log.info("Embed job already running: {}", jobId);
            return;
        }
        Future<?> future = executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Embed job execution failed: {}", jobId, e);
            } finally {
                runningTasks.remove(jobId);
            }
        });
        runningTasks.put(jobId, future);
    }

    public void cancel(String jobId) {
        Future<?> future = runningTasks.remove(jobId);
        if (future != null) future.cancel(true);
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    @PostConstruct
    public void start() {
        log.info("EmbeddingJobExecutor started with 1 thread");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down EmbeddingJobExecutor...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
