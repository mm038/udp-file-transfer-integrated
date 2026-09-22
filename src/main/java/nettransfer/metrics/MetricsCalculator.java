package nettransfer.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Pure calculations for finalized transfer metrics. */
public final class MetricsCalculator {
    private MetricsCalculator() {}

    public static double transferDurationSeconds(long startNanos, long endNanos) {
        final long elapsed;
        try {
            elapsed = Math.subtractExact(endNanos, startNanos);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("elapsed nanoseconds overflow", exception);
        }
        if (elapsed < 0) {
            throw new IllegalArgumentException("endNanos must not precede startNanos");
        }
        return elapsed / 1_000_000_000.0;
    }

    /** Returns null when delivery evidence is missing or duration is zero/unavailable. */
    public static Double throughputMbps(Long payloadBytesDelivered, Double transferTimeSeconds) {
        if (payloadBytesDelivered == null || transferTimeSeconds == null) {
            return null;
        }
        requireNonNegative("payloadBytesDelivered", payloadBytesDelivered);
        requireNonNegativeFinite("transferTimeSeconds", transferTimeSeconds);
        if (transferTimeSeconds == 0.0) {
            return null;
        }
        return finiteResult(
                "throughputMbps",
                (payloadBytesDelivered * 8.0 / transferTimeSeconds) / 1_000_000.0);
    }

    /** Returns null when either counter is missing or no DATA send attempts were observed. */
    public static Double retransmissionRatio(Long retransmissions, Long packetsSent) {
        if (retransmissions == null || packetsSent == null) {
            return null;
        }
        requireNonNegative("retransmissions", retransmissions);
        requireNonNegative("packetsSent", packetsSent);
        if (packetsSent == 0) {
            return null;
        }
        if (retransmissions > packetsSent) {
            throw new IllegalArgumentException("retransmissions cannot exceed DATA send attempts");
        }
        return (double) retransmissions / packetsSent;
    }

    /**
     * Calculates overhead only when the caller confirms complete endpoint emission and delivery
     * evidence. Missing or incomplete evidence produces an unavailable result.
     */
    public static OverheadResult protocolOverhead(
            Long udpPayloadBytesEmitted,
            Long payloadBytesDelivered,
            boolean accountingComplete) {
        if (!accountingComplete
                || udpPayloadBytesEmitted == null
                || payloadBytesDelivered == null) {
            return OverheadResult.unavailable();
        }
        requireNonNegative("udpPayloadBytesEmitted", udpPayloadBytesEmitted);
        requireNonNegative("payloadBytesDelivered", payloadBytesDelivered);
        if (payloadBytesDelivered > udpPayloadBytesEmitted) {
            throw new IllegalArgumentException(
                    "payloadBytesDelivered cannot exceed emitted UDP payload bytes");
        }

        long overheadBytes = udpPayloadBytesEmitted - payloadBytesDelivered;
        Double overheadRatio = udpPayloadBytesEmitted == 0
                ? null
                : (double) overheadBytes / udpPayloadBytesEmitted;
        return new OverheadResult(overheadBytes, overheadRatio);
    }

    /**
     * Calculates statistics for already-eligible samples. A non-null empty collection means
     * sampling occurred with zero usable samples. Null means sampling evidence is unavailable.
     * Null, negative, NaN, and infinite individual samples are rejected.
     */
    public static RttStatistics rttStatistics(Collection<Double> eligibleSamplesMillis) {
        if (eligibleSamplesMillis == null) {
            return RttStatistics.unavailable();
        }

        List<Double> samples = new ArrayList<>(eligibleSamplesMillis.size());
        for (Double sample : eligibleSamplesMillis) {
            requireNonNegativeFinite("RTT sample", sample);
            samples.add(sample);
        }
        if (samples.isEmpty()) {
            return new RttStatistics(0L, null, null);
        }

        Collections.sort(samples);
        double mean = 0.0;
        for (int index = 0; index < samples.size(); index++) {
            mean += (samples.get(index) - mean) / (index + 1);
        }
        mean = finiteResult("rttMeanMs", mean);
        int p95Index = (int) Math.ceil(0.95 * samples.size()) - 1;
        return new RttStatistics((long) samples.size(), mean, samples.get(p95Index));
    }

    private static void requireNonNegative(String field, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }

    private static void requireNonNegativeFinite(String field, Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be non-null, finite, and non-negative");
        }
    }

    private static double finiteResult(String field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " would not be finite");
        }
        return value;
    }

    public record OverheadResult(Long protocolOverheadBytes, Double protocolOverheadRatio) {
        public static OverheadResult unavailable() {
            return new OverheadResult(null, null);
        }
    }

    public record RttStatistics(Long sampleCount, Double meanMs, Double p95Ms) {
        public static RttStatistics unavailable() {
            return new RttStatistics(null, null, null);
        }
    }
}
