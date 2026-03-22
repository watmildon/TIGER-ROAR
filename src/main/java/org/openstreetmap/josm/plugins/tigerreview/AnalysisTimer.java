// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight stopwatch for recording named timing phases during analysis.
 *
 * <p>Usage:
 * <pre>
 *   AnalysisTimer timer = new AnalysisTimer();
 *   timer.start("buildIndex");
 *   // ... work ...
 *   timer.stop();
 *   timer.start("analyzeWays");
 *   // ... work ...
 *   timer.stop();
 *   System.out.println(timer.summary());
 *   // "buildIndex: 12ms | analyzeWays: 85ms | total: 97ms"
 * </pre>
 */
public class AnalysisTimer {

    private final Map<String, Long> timings = new LinkedHashMap<>();
    private long phaseStart;
    private String currentPhase;
    private long totalStart;

    /**
     * Create a new timer. The total elapsed time starts counting from construction.
     */
    public AnalysisTimer() {
        this.totalStart = System.nanoTime();
    }

    /**
     * Start timing a named phase. Stops any currently running phase first.
     *
     * @param phase descriptive name for this phase
     */
    public void start(String phase) {
        if (currentPhase != null) {
            stop();
        }
        currentPhase = phase;
        phaseStart = System.nanoTime();
    }

    /**
     * Stop the current phase and record its elapsed time.
     */
    public void stop() {
        if (currentPhase != null) {
            long elapsed = (System.nanoTime() - phaseStart) / 1_000_000;
            timings.merge(currentPhase, elapsed, Long::sum);
            currentPhase = null;
        }
    }

    /**
     * Get the total elapsed time in milliseconds since this timer was created.
     */
    public long totalMs() {
        return (System.nanoTime() - totalStart) / 1_000_000;
    }

    /**
     * Get all recorded phase timings (insertion order preserved).
     */
    public Map<String, Long> getTimings() {
        return Collections.unmodifiableMap(timings);
    }

    /**
     * Get the recorded time for a specific phase, or -1 if not recorded.
     */
    public long getPhaseMs(String phase) {
        return timings.getOrDefault(phase, -1L);
    }

    /**
     * Produce a compact summary string of all phases and total time.
     *
     * @return e.g. "buildIndex: 12ms | assignment: 340ms | total: 437ms"
     */
    public String summary() {
        stop(); // stop any running phase
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> entry : timings.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("ms");
        }
        if (sb.length() > 0) {
            sb.append(" | ");
        }
        sb.append("total: ").append(totalMs()).append("ms");
        return sb.toString();
    }
}
