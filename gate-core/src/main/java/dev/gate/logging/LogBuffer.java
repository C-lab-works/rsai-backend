package dev.gate.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class LogBuffer extends AppenderBase<ILoggingEvent> {

    private static final int MAX_ENTRIES = 300;
    private static volatile LogBuffer instance;

    private final Deque<Map<String, Object>> buffer = new ArrayDeque<>(MAX_ENTRIES);
    private final Object lock = new Object();

    public static LogBuffer get() { return instance; }

    @Override
    public void start() {
        super.start();
        instance = this;
    }

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, Object> entry = new HashMap<>(4);
        entry.put("timestamp", event.getInstant().toString());
        entry.put("level",     event.getLevel().toString());
        entry.put("logger",    abbreviate(event.getLoggerName()));
        entry.put("message",   event.getFormattedMessage());

        synchronized (lock) {
            if (buffer.size() >= MAX_ENTRIES) buffer.pollFirst();
            buffer.addLast(entry);
        }
    }

    /**
     * @param limit       最大取得件数（1–300）
     * @param levelFilter null または "INFO"/"WARN"/"ERROR"/"DEBUG"/"TRACE"（大文字小文字不問）
     */
    public List<Map<String, Object>> getRecent(int limit, String levelFilter) {
        String filter = (levelFilter != null && !levelFilter.isBlank())
                ? levelFilter.trim().toUpperCase() : null;

        List<Map<String, Object>> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(buffer);
        }

        List<Map<String, Object>> result = new ArrayList<>(Math.min(limit, snapshot.size()));
        // 新しい順に最大 limit 件取り出す
        for (int i = snapshot.size() - 1; i >= 0 && result.size() < limit; i--) {
            Map<String, Object> e = snapshot.get(i);
            if (filter == null || filter.equals(e.get("level"))) {
                result.add(e);
            }
        }
        return result;
    }

    private static String abbreviate(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
