package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ProgressTrackerService {

    private static final long RESULT_TTL_MILLIS = 5 * 60_000L;
    private static final int MAX_PROGRESS_RECORDS = 128;

    public static class ProgressInfo {
        private static final long SEARCH_LEASE_MILLIS = 45_000L;
        private final int total;
        private final String owner;
        private final long createdAt = System.currentTimeMillis();
        private final List<JsonNode> results = new ArrayList<>();
        private final List<Runnable> cancelHooks = new ArrayList<>();
        private int completed;
        private int failed;
        private String currentSource = "";
        private String error;
        private boolean done;
        private boolean cancelled;
        private long lastAccess = createdAt;
        private long finishedAt;

        public ProgressInfo(int total) {
            this(total, null);
        }

        public ProgressInfo(int total, String owner) {
            this.total = total;
            this.owner = owner;
        }

        public synchronized int getTotal() {
            return total;
        }

        public synchronized int getCompleted() {
            return completed;
        }

        public synchronized int getFailed() {
            return failed;
        }

        public synchronized String getCurrentSource() {
            return currentSource;
        }

        public synchronized boolean isDone() {
            return done;
        }

        public synchronized boolean isCancelled() {
            return cancelled;
        }

        public synchronized List<JsonNode> getResults() {
            return new ArrayList<>(results);
        }

        public synchronized void incrementCompleted() {
            if (!done) {
                completed++;
            }
        }

        public synchronized void incrementFailed() {
            if (!done) {
                failed++;
            }
        }

        public synchronized void setCurrentSource(String source) {
            if (!done) {
                currentSource = source;
            }
        }

        public synchronized void setDone(boolean value) {
            if (value && !done) {
                done = true;
                finishedAt = System.currentTimeMillis();
                cancelHooks.clear();
            }
        }

        public synchronized void addResult(JsonNode result) {
            if (!done) {
                results.add(result);
            }
        }

        public synchronized void completeSource(List<JsonNode> rows, boolean failure) {
            if (!done) {
                results.addAll(rows);
                completed++;
                if (failure) {
                    failed++;
                }
            }
        }

        public synchronized void touch() {
            lastAccess = System.currentTimeMillis();
        }

        public boolean isActive() {
            boolean abandoned;
            synchronized (this) {
                if (done) {
                    return false;
                }
                abandoned = System.currentTimeMillis() - lastAccess > SEARCH_LEASE_MILLIS;
            }
            if (abandoned) {
                cancel();
                return false;
            }
            return true;
        }

        public boolean addCancelHook(Runnable hook) {
            synchronized (this) {
                if (!done) {
                    cancelHooks.add(hook);
                    return true;
                }
            }
            hook.run();
            return false;
        }

        public synchronized void removeCancelHook(Runnable hook) {
            cancelHooks.remove(hook);
        }

        public void cancel() {
            terminate(null, true);
        }

        public void fail(String message) {
            terminate(message, false);
        }

        private void terminate(String message, boolean wasCancelled) {
            List<Runnable> hooks;
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                cancelled = wasCancelled;
                error = message;
                finishedAt = System.currentTimeMillis();
                hooks = new ArrayList<>(cancelHooks);
                cancelHooks.clear();
            }
            for (Runnable hook : hooks) {
                hook.run();
            }
        }

        public synchronized ObjectNode snapshot() {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            result.put("found", true);
            result.put("total", total);
            result.put("completed", completed);
            result.put("failed", failed);
            result.put("currentSource", currentSource);
            result.put("done", done);
            result.put("cancelled", cancelled);
            result.set("results", JsonNodeFactory.instance.arrayNode().addAll(results));
            if (error != null) {
                result.put("error", error);
            }
            return result;
        }

        private synchronized boolean expired(long now) {
            return done && now - finishedAt > RESULT_TTL_MILLIS;
        }
    }

    private final ConcurrentMap<String, ProgressInfo> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeSearches = new ConcurrentHashMap<>();

    public String createProgress(int total) {
        return createProgress(total, null);
    }

    public synchronized String createProgress(int total, String owner) {
        cleanup();
        String id = UUID.randomUUID().toString();
        progressMap.put(id, new ProgressInfo(total, owner));
        return id;
    }

    public synchronized String createSearchProgress(int total, String owner) {
        cancelActiveSearch(owner);
        String id = createProgress(total, owner);
        activeSearches.put(owner, id);
        return id;
    }

    public void cancelActiveSearch(String owner) {
        String previousId = activeSearches.get(owner);
        ProgressInfo previous = previousId == null ? null : progressMap.get(previousId);
        if (previous != null) {
            previous.cancel();
        }
    }

    public ProgressInfo getProgress(String id) {
        return progressMap.get(id);
    }

    public ProgressInfo getProgress(String id, String owner) {
        ProgressInfo progress = progressMap.get(id);
        if (progress == null || !Objects.equals(progress.owner, owner)) {
            return null;
        }
        if (progress.expired(System.currentTimeMillis())) {
            removeProgress(id);
            return null;
        }
        progress.touch();
        return progress;
    }

    public void removeProgress(String id) {
        ProgressInfo progress = progressMap.remove(id);
        if (progress != null) {
            progress.cancel();
            if (progress.owner != null) {
                activeSearches.remove(progress.owner, id);
            }
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        progressMap.forEach((id, progress) -> {
            if (progress.expired(now)) {
                removeProgress(id);
            }
        });
        while (progressMap.size() >= MAX_PROGRESS_RECORDS) {
            String oldest = progressMap.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().createdAt))
                    .map(java.util.Map.Entry::getKey).orElse(null);
            if (oldest == null) {
                break;
            }
            removeProgress(oldest);
        }
    }
}
