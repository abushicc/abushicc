package com.example.relationshipagent.analysis.job;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Single analysis worker prevents concurrent expensive model calls in the local deployment.
 */
@Component
public class AnalysisJobExecutor {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analysis-job-worker");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Future<?>> tasks = new ConcurrentHashMap<>();

    public void submit(String jobId, Runnable work) {
        tasks.computeIfAbsent(jobId, ignored -> executor.submit(() -> {
            try {
                work.run();
            } finally {
                tasks.remove(jobId);
            }
        }));
    }

    public void cancel(String jobId) {
        Future<?> future = tasks.remove(jobId);
        if (future != null) future.cancel(true);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
