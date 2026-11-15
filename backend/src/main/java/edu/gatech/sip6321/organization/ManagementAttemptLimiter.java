package edu.gatech.sip6321.organization;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Bounded per-instance OWNER lookup throttle, including failed lookups; never stores emails. */
@Component
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class ManagementAttemptLimiter {
    private record Window(long start, int attempts) {}

    private final Map<UUID, Window> windows = new HashMap<>();

    public synchronized void attempt(UUID owner) {
        long now = System.nanoTime();
        long duration = 60_000_000_000L;
        windows.entrySet().removeIf(e -> now - e.getValue().start() >= duration);
        Window window = windows.get(owner);
        if (window == null) {
            if (windows.size() >= 4096) throw OrganizationFailure.rateLimited();
            windows.put(owner, new Window(now, 1));
        } else {
            if (window.attempts() >= 10) throw OrganizationFailure.rateLimited();
            windows.put(owner, new Window(window.start(), window.attempts() + 1));
        }
    }
}
