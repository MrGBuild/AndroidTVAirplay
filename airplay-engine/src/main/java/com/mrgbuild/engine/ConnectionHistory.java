package com.mrgbuild.engine;

import java.util.ArrayDeque;

/** Bounded in-memory diagnostics. Callers supply protocol labels, never sender data. */
public final class ConnectionHistory {
    private final ArrayDeque<String> events = new ArrayDeque<>();
    public synchronized void add(String event) {
        if (events.size() == 12) events.removeFirst();
        events.addLast(event);
    }
    public synchronized String snapshot() {
        return events.isEmpty() ? "No TCP connections received since receiver startup."
                : String.join("\n", events);
    }
}
