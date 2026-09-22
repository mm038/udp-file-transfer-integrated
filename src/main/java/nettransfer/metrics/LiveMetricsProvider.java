package nettransfer.metrics;

/** Read-only capability for obtaining one atomic endpoint-local live observation. */
public interface LiveMetricsProvider {
    LiveMetricsSnapshot getLiveMetricsSnapshot();
}
