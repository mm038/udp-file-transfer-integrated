package nettransfer.transfer;

import nettransfer.metrics.EventLogger;
import nettransfer.metrics.EventType;
import nettransfer.metrics.MetricsCollector;
import nettransfer.metrics.TransferConfiguration;
import nettransfer.metrics.TransferContext;
import nettransfer.metrics.TransferEvent;
import nettransfer.net.ImpairmentObservation;
import nettransfer.net.ImpairmentSettings;
import nettransfer.net.UdpChannel;

import java.net.InetAddress;
import java.util.Objects;

/** Binds simulator decisions to one established peer/run and its ordinary evidence stream. */
final class TransferImpairmentLifecycle {
    private final UdpChannel channel;
    private final TransferContext context;
    private final MetricsCollector collector;
    private final EventLogger logger;
    private boolean finished;

    private TransferImpairmentLifecycle(UdpChannel channel, TransferContext context,
                                       MetricsCollector collector, EventLogger logger) {
        this.channel = channel;
        this.context = context;
        this.collector = collector;
        this.logger = logger;
    }

    static TransferConfiguration effectiveConfiguration(TransferConfiguration configuration,
                                                       ImpairmentSettings settings) {
        if (!settings.enabled()) {
            if (configuration.getImpairmentMechanism() != null) {
                throw new IllegalArgumentException("Impairment metadata requires an active simulator");
            }
            return configuration;
        }
        requireMatching("impairment mechanism", configuration.getImpairmentMechanism(),
                ImpairmentSettings.MECHANISM);
        requireMatching("loss percentage", configuration.getPacketLossRate(), settings.lossPercent());
        requireMatching("delay", configuration.getDelayMs(), (double) settings.delayMillis());
        requireMatching("scenario", configuration.getScenario(), settings.scenario());
        requireMatching("impairment seed", configuration.getImpairmentSeed(), settings.seed());
        return configuration.toBuilder().impairmentMechanism(ImpairmentSettings.MECHANISM)
                .packetLossRate(settings.lossPercent()).delayMs((double) settings.delayMillis())
                .scenario(settings.scenario()).impairmentSeed(settings.seed()).build();
    }

    private static void requireMatching(String name, Object supplied, Object actual) {
        if (supplied != null && !Objects.equals(supplied, actual)) {
            throw new IllegalArgumentException(name + " conflicts with the actual simulator settings");
        }
    }

    static TransferImpairmentLifecycle begin(UdpChannel channel, TransferContext context,
                                             MetricsCollector collector, EventLogger logger,
                                             InetAddress peer, int port) {
        if (!channel.getImpairmentSettings().enabled()) {
            return null;
        }
        var lifecycle = new TransferImpairmentLifecycle(channel, context, collector, logger);
        channel.beginImpairment(context.getEndpoint().name().toLowerCase(java.util.Locale.ROOT),
                peer, port, context.getProtocolTransferId(), lifecycle::observe);
        collector.beginImpairmentObservation();
        logger.record(context, EventType.IMPAIRMENT_STARTED, TransferEvent.Direction.LOCAL,
                TransferEvent.Details.builder().eventOutcome(ImpairmentSettings.MECHANISM).build());
        return lifecycle;
    }

    private void observe(ImpairmentObservation observation) {
        EventType type = switch (observation.action()) {
            case "DROP" -> EventType.IMPAIRMENT_DROPPED;
            case "DELAY" -> EventType.IMPAIRMENT_DELAYED;
            case "PASS", "DELIVER" -> EventType.IMPAIRMENT_DELIVERED;
            case "CANCEL" -> EventType.IMPAIRMENT_CANCELLED;
            case "QUEUE_OVERFLOW" -> EventType.IMPAIRMENT_FAILED;
            default -> throw new IllegalArgumentException("Unknown simulator observation");
        };
        if (type == EventType.IMPAIRMENT_DROPPED) {
            collector.observeImpairmentDrop();
        }
        var details = TransferEvent.Details.builder()
                .messageType(observation.messageType())
                .impairmentDecisionIndex(observation.decisionIndex())
                .impairmentDelayMs(observation.delayMillis())
                .eventOutcome(observation.action()).failureReason(observation.reason());
        if ("ACK".equals(observation.messageType())) {
            details.ackNumber(observation.sequenceNumber());
        } else {
            details.sequenceNumber(observation.sequenceNumber());
        }
        logger.record(context, type, TransferEvent.Direction.INBOUND, details.build());
    }

    void finish() {
        if (!finished) {
            channel.finishImpairment();
            logger.record(context, EventType.IMPAIRMENT_FINISHED, TransferEvent.Direction.LOCAL,
                    TransferEvent.Details.builder().eventOutcome("DECISIONS_FINALIZED").build());
            finished = true;
        }
    }
}
