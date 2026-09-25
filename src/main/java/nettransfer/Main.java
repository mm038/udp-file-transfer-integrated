package nettransfer;

import nettransfer.net.UdpChannel;
import nettransfer.net.ImpairmentSettings;
import nettransfer.metrics.TransferConfiguration;
import nettransfer.metrics.TransferContext;
import nettransfer.metrics.EventLogger;
import nettransfer.metrics.MetricsExporter;
import nettransfer.storage.FileStorageManager;
import nettransfer.transfer.ReceiverEngine;
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real CLI entry point, using SenderEngine/ReceiverEngine (Stage 10.5) --
 * replaces the Stage 2 throwaway handshake-only demo.
 *
 * Usage:
 *   Receiver: mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=receiver 9000 received.bin"
 *   Sender:   mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=sender 9000 mydata.bin"
 *
 * Run the receiver first, in one terminal, then the sender in a second.
 */
public class Main {

    private static final int CHUNK_SIZE = 1024;
    private static final int WINDOW_SIZE = 1; // stop-and-wait default; raise for sliding-window demo
    private static final int TIMEOUT_MILLIS = 200;
    private static final int RETRY_LIMIT = 5;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.out.println("Usage: Main <sender|receiver> <port> <filename>");
            System.out.println("   or: Main reconcile <sender-run-dir> <receiver-run-dir>");
            System.out.println("Simulator JVM settings (identical on both endpoints):");
            System.out.println("  -Dnettransfer.impairment.enabled=true -Dnettransfer.impairment.lossPercent=2");
            System.out.println("  -Dnettransfer.impairment.delayMs=0 -Dnettransfer.impairment.seed=42 -Dnettransfer.impairment.scenario=loss-2");
            return;
        }

        String mode = args[0];
        if (mode.equals("reconcile")) {
            MetricsExporter.ExportResult export = MetricsExporter.reconcile(
                    Path.of(args[1]), Path.of(args[2]));
            System.out.println("[metrics] Summary: " + export.summaryPath());
            System.out.println("[metrics] Manifest: " + export.manifestPath());
            return;
        }
        int port = Integer.parseInt(args[1]);
        String filename = args[2];
        FileStorageManager storage = new FileStorageManager();

        if (mode.equals("receiver")) {
            runReceiver(port, filename, storage);
        } else if (mode.equals("sender")) {
            runSender(port, filename, storage);
        } else {
            System.out.println("Unknown mode: " + mode + " (expected 'sender' or 'receiver')");
        }
    }

    private static void runReceiver(int port, String filename, FileStorageManager storage) throws Exception {
        runReceiver(port, filename, storage,
                configuredReceiverInitialTimeoutMillis(),
                configuredReceiverInactivityTimeoutMillis(),
                configuredReceiverCompletionGraceMillis());
    }

    static void runReceiver(int port, String filename, FileStorageManager storage,
                            int initialTimeoutMillis, int inactivityTimeoutMillis) throws Exception {
        runReceiver(port, filename, storage, initialTimeoutMillis, inactivityTimeoutMillis,
                ReceiverEngine.DEFAULT_COMPLETION_GRACE_MS);
    }

    static void runReceiver(int port, String filename, FileStorageManager storage,
                            int initialTimeoutMillis, int inactivityTimeoutMillis,
                            int completionGraceMillis) throws Exception {
        ImpairmentSettings impairment = ImpairmentSettings.fromSystemProperties();
        reportImpairment(impairment);
        Path destination = storage.resolveIncomingFile(filename);
        Path temporaryFile = storage.createIncomingTemporaryFile();
        System.out.println("[receiver] Binding UDP socket on port " + port + " ...");
        try (UdpChannel channel = new UdpChannel(port, impairment)) {
            System.out.println("[receiver] Waiting for a transfer...");
            TransferContext context = TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                    .fileAttribution("storage/incoming/" + filename)
                    .configuration(TransferConfiguration.builder()
                            .receiverInitialTimeoutMs((long) initialTimeoutMillis)
                            .receiverInactivityTimeoutMs((long) inactivityTimeoutMillis)
                            .receiverCompletionGraceMs((long) completionGraceMillis)
                            .build())
                    .build();
            ReceiverEngine receiver = new ReceiverEngine(
                    channel, initialTimeoutMillis, inactivityTimeoutMillis,
                    completionGraceMillis, context);
            EventLogger eventLogger = receiver.enableEventLogging(configuredLogsRoot());
            System.out.println("[receiver] Event log: " + eventLogger.getEventsFile());
            TransferResult result = receiver.receiveFile(temporaryFile.toString());
            if (result.isSuccess()) {
                storage.publishIncomingFile(temporaryFile, filename);
                System.out.println("[receiver] Saved " + destination);
            }
            System.out.println("[receiver] " + result);
            reportLoggingFailure(eventLogger);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static void runSender(int port, String filename, FileStorageManager storage) throws Exception {
        ImpairmentSettings impairment = ImpairmentSettings.fromSystemProperties();
        reportImpairment(impairment);
        Path file = storage.resolveOutgoingFile(filename);
        System.out.println("[sender] Opening UDP socket on an ephemeral port...");
        try (UdpChannel channel = new UdpChannel(impairment)) {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            int startTimeoutMillis = configuredStartHandshakeTimeoutMillis();
            int startRetryLimit = configuredStartRetryLimit();
            int finishTimeoutMillis = configuredFinishHandshakeTimeoutMillis();
            int finishRetryLimit = configuredFinishRetryLimit();
            TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                    .fileAttribution("storage/outgoing/" + filename)
                    .configuration(TransferConfiguration.builder()
                            .chunkSizeBytes((long) CHUNK_SIZE)
                            .windowPackets((long) WINDOW_SIZE)
                            .timeoutMs((long) TIMEOUT_MILLIS)
                            .retryLimit((long) RETRY_LIMIT)
                            .startHandshakeTimeoutMs((long) startTimeoutMillis)
                            .startRetryLimit((long) startRetryLimit)
                            .finishHandshakeTimeoutMs((long) finishTimeoutMillis)
                            .finishRetryLimit((long) finishRetryLimit)
                            .build())
                    .build();
            SenderEngine sender = new SenderEngine(
                    channel, loopback, port, CHUNK_SIZE, WINDOW_SIZE, TIMEOUT_MILLIS, RETRY_LIMIT,
                    startTimeoutMillis, startRetryLimit,
                    finishTimeoutMillis, finishRetryLimit, context);
            EventLogger eventLogger = sender.enableEventLogging(configuredLogsRoot());
            System.out.println("[sender] Event log: " + eventLogger.getEventsFile());
            System.out.println("[sender] Sending " + file + " to 127.0.0.1:" + port);
            TransferResult result = sender.sendFile(file.toString());
            System.out.println("[sender] " + result);
            reportLoggingFailure(eventLogger);
        }
    }

    static int configuredReceiverInitialTimeoutMillis() {
        return Integer.getInteger("nettransfer.receiverInitialTimeoutMs",
                ReceiverEngine.DEFAULT_INITIAL_TIMEOUT_MS);
    }

    static int configuredReceiverInactivityTimeoutMillis() {
        return Integer.getInteger("nettransfer.receiverInactivityTimeoutMs",
                ReceiverEngine.DEFAULT_INACTIVITY_TIMEOUT_MS);
    }

    static int configuredStartHandshakeTimeoutMillis() {
        return Integer.getInteger("nettransfer.startHandshakeTimeoutMs",
                SenderEngine.DEFAULT_START_HANDSHAKE_TIMEOUT_MS);
    }

    static int configuredStartRetryLimit() {
        return Integer.getInteger("nettransfer.startRetryLimit",
                SenderEngine.DEFAULT_START_RETRY_LIMIT);
    }

    static int configuredFinishHandshakeTimeoutMillis() {
        return Integer.getInteger("nettransfer.finishHandshakeTimeoutMs",
                SenderEngine.DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS);
    }

    static int configuredFinishRetryLimit() {
        return Integer.getInteger("nettransfer.finishRetryLimit",
                SenderEngine.DEFAULT_FINISH_RETRY_LIMIT);
    }

    static int configuredReceiverCompletionGraceMillis() {
        long grace = (long) configuredFinishHandshakeTimeoutMillis()
                * (configuredFinishRetryLimit() + 1L) + 250L;
        if (grace > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Configured FINISH recovery window is too large");
        }
        return (int) grace;
    }

    static Path configuredLogsRoot() {
        return Path.of(System.getProperty("nettransfer.logsRoot", "logs"));
    }

    private static void reportLoggingFailure(EventLogger logger) {
        if (logger.getLoggingFailure() != null) {
            System.err.println("[event-logger] Evidence is incomplete: " + logger.getLoggingFailure());
        }
    }

    private static void reportImpairment(ImpairmentSettings settings) {
        if (settings.enabled()) {
            System.out.println("[impairment] " + settings.scenario() + ": receiver DATA loss="
                    + settings.lossPercent() + "%, DATA/ACK delivery delay=" + settings.delayMillis()
                    + " ms per direction, seed=" + settings.seed() + "; START/FINISH unaffected");
        }
    }
}
