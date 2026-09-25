package nettransfer.net;

import java.util.UUID;

/** One auditable decision or lifecycle event for a transfer-scoped datagram. */
public record ImpairmentObservation(String action, String messageType, int sequenceNumber,
                                    long decisionIndex, int delayMillis, UUID transferId,
                                    String reason) {
}
