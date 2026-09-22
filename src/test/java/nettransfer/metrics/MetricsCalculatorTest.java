package nettransfer.metrics;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsCalculatorTest {
    @Test
    void calculatesDurationFromMonotonicNanoseconds() {
        assertEquals(1.5, MetricsCalculator.transferDurationSeconds(2_000_000_000L, 3_500_000_000L));
        assertEquals(0.0, MetricsCalculator.transferDurationSeconds(7L, 7L));
    }

    @Test
    void rejectsNegativeOrOverflowingDuration() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.transferDurationSeconds(2L, 1L)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.transferDurationSeconds(Long.MIN_VALUE, Long.MAX_VALUE)));
    }

    @Test
    void calculatesDecimalMbpsForCompleteAndPartialDelivery() {
        assertAll(
                () -> assertEquals(4.0, MetricsCalculator.throughputMbps(1_000_000L, 2.0)),
                () -> assertEquals(0.002, MetricsCalculator.throughputMbps(500L, 2.0)),
                () -> assertEquals(0.0, MetricsCalculator.throughputMbps(0L, 2.0)));
    }

    @Test
    void throughputIsUnavailableWithoutEvidenceOrPositiveDuration() {
        assertAll(
                () -> assertNull(MetricsCalculator.throughputMbps(null, 1.0)),
                () -> assertNull(MetricsCalculator.throughputMbps(1L, null)),
                () -> assertNull(MetricsCalculator.throughputMbps(1L, 0.0)));
    }

    @Test
    void rejectsInvalidOrNonFiniteThroughputInputsAndResults() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.throughputMbps(-1L, 1.0)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.throughputMbps(1L, Double.NaN)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.throughputMbps(Long.MAX_VALUE, Double.MIN_VALUE)));
    }

    @Test
    void calculatesRetransmissionRatioAsAFraction() {
        assertEquals(0.2, MetricsCalculator.retransmissionRatio(2L, 10L));
    }

    @Test
    void retransmissionRatioHandlesMissingZeroAndInconsistentCounters() {
        assertAll(
                () -> assertNull(MetricsCalculator.retransmissionRatio(null, 10L)),
                () -> assertNull(MetricsCalculator.retransmissionRatio(0L, null)),
                () -> assertNull(MetricsCalculator.retransmissionRatio(0L, 0L)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.retransmissionRatio(-1L, 10L)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.retransmissionRatio(11L, 10L)));
    }

    @Test
    void calculatesProtocolOverheadFromCompleteAccounting() {
        MetricsCalculator.OverheadResult result =
                MetricsCalculator.protocolOverhead(1_500L, 1_000L, true);

        assertAll(
                () -> assertEquals(500L, result.protocolOverheadBytes()),
                () -> assertEquals(1.0 / 3.0, result.protocolOverheadRatio()));
    }

    @Test
    void overheadIsUnavailableWhenAccountingIsIncompleteOrEvidenceMissing() {
        MetricsCalculator.OverheadResult incomplete =
                MetricsCalculator.protocolOverhead(1_500L, 1_000L, false);
        MetricsCalculator.OverheadResult missing =
                MetricsCalculator.protocolOverhead(null, 1_000L, true);

        assertAll(
                () -> assertNull(incomplete.protocolOverheadBytes()),
                () -> assertNull(incomplete.protocolOverheadRatio()),
                () -> assertNull(missing.protocolOverheadBytes()),
                () -> assertNull(missing.protocolOverheadRatio()));
    }

    @Test
    void zeroEmittedBytesHasObservedZeroOverheadAndUnavailableRatio() {
        MetricsCalculator.OverheadResult result =
                MetricsCalculator.protocolOverhead(0L, 0L, true);

        assertAll(
                () -> assertEquals(0L, result.protocolOverheadBytes()),
                () -> assertNull(result.protocolOverheadRatio()));
    }

    @Test
    void rejectsNegativeOrInconsistentOverheadEvidence() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.protocolOverhead(-1L, 0L, true)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.protocolOverhead(10L, 11L, true)));
    }

    @Test
    void calculatesRttMeanAndNearestRankP95WithoutMutatingInput() {
        List<Double> samples = new ArrayList<>();
        for (int value = 20; value >= 1; value--) {
            samples.add((double) value);
        }
        List<Double> originalOrder = List.copyOf(samples);

        MetricsCalculator.RttStatistics statistics = MetricsCalculator.rttStatistics(samples);

        assertAll(
                () -> assertEquals(20L, statistics.sampleCount()),
                () -> assertEquals(10.5, statistics.meanMs()),
                () -> assertEquals(19.0, statistics.p95Ms()),
                () -> assertEquals(originalOrder, samples));
    }

    @Test
    void distinguishesZeroEligibleSamplesFromUnavailableSampling() {
        MetricsCalculator.RttStatistics empty = MetricsCalculator.rttStatistics(List.of());
        MetricsCalculator.RttStatistics unavailable = MetricsCalculator.rttStatistics(null);

        assertAll(
                () -> assertEquals(0L, empty.sampleCount()),
                () -> assertNull(empty.meanMs()),
                () -> assertNull(empty.p95Ms()),
                () -> assertNull(unavailable.sampleCount()),
                () -> assertNull(unavailable.meanMs()),
                () -> assertNull(unavailable.p95Ms()));
    }

    @Test
    void rejectsEveryInvalidRttSampleKind() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.rttStatistics(Arrays.asList(1.0, null))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.rttStatistics(List.of(-0.1))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.rttStatistics(List.of(Double.NaN))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> MetricsCalculator.rttStatistics(List.of(Double.POSITIVE_INFINITY))));
    }

    @Test
    void calculatedNumericOutputsAreFinite() {
        MetricsCalculator.OverheadResult overhead =
                MetricsCalculator.protocolOverhead(12L, 10L, true);
        MetricsCalculator.RttStatistics rtt = MetricsCalculator.rttStatistics(List.of(1.0, 2.0));

        assertAll(
                () -> assertFalse(MetricsCalculator.throughputMbps(1L, 1.0).isNaN()),
                () -> assertFalse(MetricsCalculator.throughputMbps(1L, 1.0).isInfinite()),
                () -> assertFalse(MetricsCalculator.retransmissionRatio(1L, 2L).isNaN()),
                () -> assertFalse(overhead.protocolOverheadRatio().isInfinite()),
                () -> assertFalse(rtt.meanMs().isNaN()),
                () -> assertFalse(rtt.p95Ms().isInfinite()));
    }
}
