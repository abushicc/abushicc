package com.example.relationshipagent.processing;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 异步任务执行器（设计文档 17.8）。
 * <p>
 * 使用固定线程池，不使用 @Async（无持久化能力）。
 * 本地单用户，2 线程足够。
 */
@Component
public class ProcessingJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobExecutor.class);

    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public ProcessingJobExecutor() {
        this.executor = new ThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.executor.setThreadFactory(r -> {
            Thread t = new Thread(r, "job-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 提交异步任务。
     *
     * @param jobId 任务 ID，用于去重
     * @param task  实际执行的 Consumer，接收 jobId
     */
    public void submit(String jobId, Runnable task) {
        if (runningTasks.containsKey(jobId)) {
            log.info("Job already running: {}", jobId);
            return;
        }
        Future<?> future = executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Job execution failed: {}", jobId, e);
            } finally {
                runningTasks.remove(jobId);
            }
        });
        runningTasks.put(jobId, future);
    }

    /**
     * 取消任务。
     */
    public void cancel(String jobId) {
        Future<?> future = runningTasks.remove(jobId);
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * 检查任务是否在运行。
     */
    public boolean isRunning(String jobId) {
        return runningTasks.containsKey(jobId);
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    @PostConstruct
    public void start() {
        log.info("ProcessingJobExecutor started with 2 threads");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ProcessingJobExecutor...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
