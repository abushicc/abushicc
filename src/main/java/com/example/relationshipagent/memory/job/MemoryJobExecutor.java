package com.example.relationshipagent.memory.job;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Separate single worker bounds expensive Memory model calls in the local deployment.
 */
@Component
public class MemoryJobExecutor {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-job-worker");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Future<?>> tasks = new ConcurrentHashMap<>();

    public void submit(String id, Runnable work) {
        tasks.computeIfAbsent(id, ignored -> executor.submit(() -> {
            try {
                work.run();
            } finally {
                tasks.remove(id);
            }
        }));
    }

    public void cancel(String id) {
        Future<?> f = tasks.remove(id);
        if (f != null) f.cancel(true);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
