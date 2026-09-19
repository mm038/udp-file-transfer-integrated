package nettransfer;

import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * STAGE 2 THROWAWAY MAIN.
 *
 * Demonstrates the metadata handshake: sender proposes a transfer via a
 * START message (filename, file size, chunk size), receiver decodes it,
 * decides accept/reject, and replies with START_ACK. Still no real file
 * reading, no DATA packets, no reliability (timeouts/retries/loss handling)
 * — that's later stages. This is the "happy path" handshake only, and will
 * be deleted/replaced once nettransfer.cli.Cli and
 * nettransfer.control.TransferController exist (Stage 12+).
 *
 * Usage:
 *   mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=receiver"
 *   mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=sender"
 *
 * Run the receiver first, in one terminal. Then run the sender in a second
 * terminal.
 */
public class Main {

    private static final int PORT = 9000;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: Main <sender|receiver>");
            return;
        }

        if (args[0].equals("receiver")) {
            runReceiver();
        } else if (args[0].equals("sender")) {
            runSender();
        } else {
            System.out.println("Unknown mode: " + args[0] + " (expected 'sender' or 'receiver')");
        }
    }

    private static void runReceiver() throws Exception {
        System.out.println("[receiver] Binding UDP socket on port " + PORT + " ...");
        try (UdpChannel channel = new UdpChannel(PORT)) {
            System.out.println("[receiver] Waiting for a START message...");

            UdpChannel.ReceivedDatagram datagram = channel.receive();
            String json = new String(datagram.data(), StandardCharsets.UTF_8);
            ControlMessage start = ControlMessage.fromJson(json);

            System.out.println("[receiver] Got " + start.getType() + " for transfer "
                    + start.getTransferId() + " -> file=\"" + start.getFilename()
                    + "\", size=" + start.getFileSize() + " bytes, chunkSize=" + start.getChunkSize());

            // Minimal acceptance check for Stage 2 only. This is NOT the real
            // command/config validator (that's Stage 12) — just a sanity check
            // so START_ACK's accept/reject path actually has real logic behind it.
            boolean chunkSizeOk = start.getChunkSize() > 0 && start.getChunkSize() <= 1024;

            ControlMessage ack;
            if (chunkSizeOk) {
                ack = ControlMessage.createStartAck(start.getTransferId(), true, null);
                System.out.println("[receiver] Accepting transfer " + start.getTransferId());
            } else {
                ack = ControlMessage.createStartAck(start.getTransferId(), false,
                        "chunkSize must be between 1 and 1024, was " + start.getChunkSize());
                System.out.println("[receiver] Rejecting transfer " + start.getTransferId()
                        + ": " + ack.getErrorMessage());
            }

            byte[] ackBytes = ack.toJson().getBytes(StandardCharsets.UTF_8);
            channel.send(ackBytes, datagram.senderAddress(), datagram.senderPort());
            System.out.println("[receiver] Sent START_ACK back to "
                    + datagram.senderAddress() + ":" + datagram.senderPort());
        }
    }

    private static void runSender() throws Exception {
        System.out.println("[sender] Opening UDP socket on an ephemeral port...");
        try (UdpChannel channel = new UdpChannel()) {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");

            // Hardcoded example "file" for now — real file reading comes later
            // (Stage 3 chunking). We just need something to negotiate about.
            ControlMessage start = ControlMessage.createStart("data.bin", 204800L, 1024);

            byte[] startBytes = start.toJson().getBytes(StandardCharsets.UTF_8);
            System.out.println("[sender] Sending START for transfer " + start.getTransferId()
                    + " (\"" + start.getFilename() + "\", " + start.getFileSize() + " bytes, chunkSize="
                    + start.getChunkSize() + ") to " + loopback + ":" + PORT);
            channel.send(startBytes, loopback, PORT);

            System.out.println("[sender] Waiting for START_ACK...");
            UdpChannel.ReceivedDatagram reply = channel.receive();
            ControlMessage ack = ControlMessage.fromJson(new String(reply.data(), StandardCharsets.UTF_8));

            if (ack.getType() != MessageType.START_ACK) {
                System.out.println("[sender] Unexpected message type: " + ack.getType());
                return;
            }

            if (ack.isAccepted()) {
                System.out.println("[sender] Transfer " + ack.getTransferId() + " ACCEPTED. Ready to send data (future stage).");
            } else {
                System.out.println("[sender] Transfer " + ack.getTransferId() + " REJECTED: " + ack.getErrorMessage());
            }
        }
    }
}