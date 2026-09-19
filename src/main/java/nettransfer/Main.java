package nettransfer;

import nettransfer.net.UdpChannel;
import nettransfer.transfer.ReceiverEngine;
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;

import java.net.InetAddress;

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
            System.out.println("Usage: Main <sender|receiver> <port> <filePath>");
            return;
        }

        String mode = args[0];
        int port = Integer.parseInt(args[1]);
        String filePath = args[2];

        if (mode.equals("receiver")) {
            runReceiver(port, filePath);
        } else if (mode.equals("sender")) {
            runSender(port, filePath);
        } else {
            System.out.println("Unknown mode: " + mode + " (expected 'sender' or 'receiver')");
        }
    }

    private static void runReceiver(int port, String outputFilePath) throws Exception {
        System.out.println("[receiver] Binding UDP socket on port " + port + " ...");
        try (UdpChannel channel = new UdpChannel(port)) {
            System.out.println("[receiver] Waiting for a transfer...");
            ReceiverEngine receiver = new ReceiverEngine(channel);
            TransferResult result = receiver.receiveFile(outputFilePath);
            System.out.println("[receiver] " + result);
        }
    }

    private static void runSender(int port, String filePath) throws Exception {
        System.out.println("[sender] Opening UDP socket on an ephemeral port...");
        try (UdpChannel channel = new UdpChannel()) {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            SenderEngine sender = new SenderEngine(
                    channel, loopback, port, CHUNK_SIZE, WINDOW_SIZE, TIMEOUT_MILLIS, RETRY_LIMIT);
            System.out.println("[sender] Sending " + filePath + " to 127.0.0.1:" + port);
            TransferResult result = sender.sendFile(filePath);
            System.out.println("[sender] " + result);
        }
    }
}