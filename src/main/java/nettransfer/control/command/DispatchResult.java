package nettransfer.control.command;

import nettransfer.control.TransferError;
import nettransfer.control.TransferSettings;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferStart;
import nettransfer.control.TransferSummary;

/** Typed Java outcomes for a future CLI to render; none is an LLM claim about execution. */
public sealed interface DispatchResult {
    record Started(TransferStart acknowledgement, TransferSettings settings) implements DispatchResult {
        /** Displays the requested budget and the capacity left after rounding down to packet slots. */
        public String settingsDescription() {
            long effectiveBytes = (long) settings.windowPackets() * settings.chunkSizeBytes();
            return "Requested window: " + settings.requestedWindowBytes() + " bytes; effective window: "
                    + settings.windowPackets() + (settings.windowPackets() == 1 ? " packet (" : " packets (")
                    + effectiveBytes + " bytes); timeout: "
                    + settings.timeoutMillis() + " ms; retry limit: " + settings.retryLimit()
                    + " consecutive rounds without progress";
        }
    }

    record Status(TransferSnapshot snapshot) implements DispatchResult { }

    /** Frozen selected evidence and the user's question, not generated explanatory prose. */
    record SummarySelected(TransferSummary summary, String question) implements DispatchResult { }

    record Clarification(String question) implements DispatchResult { }

    record Unsupported(String reason) implements DispatchResult { }

    record Rejected(TransferError error) implements DispatchResult { }
}
