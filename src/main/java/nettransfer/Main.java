package nettransfer;

import nettransfer.net.UdpChannel;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * STAGE 1 THROWAWAY MAIN.
 *
 * This class exists only to prove that two Java processes can exchange
 * bytes over UDP on localhost. It has no relationship to the final
 * protocol (no headers, no ACKs, no reliability). It will be deleted /
 * replaced once nettransfer.cli.Cli and nettransfer.control.TransferController
 * exist (Stage 12+).
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="nettransfer.Main" -Dexec.args="receiver"
 *   mvn exec:java -Dexec.mainClass="nettransfer.Main" -Dexec.args="sender"
 *
 * Run the receiver first, in one terminal. Then run the sender in a second
 * terminal. The receiver should print the message the sender sent.
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
            System.out.println("[receiver] Waiting for a datagram (blocking on receive())...");
            UdpChannel.ReceivedDatagram datagram = channel.receive();
            String message = new String(datagram.data(), StandardCharsets.UTF_8);
            System.out.println("[receiver] Got " + datagram.data().length + " bytes from "
                    + datagram.senderAddress() + ":" + datagram.senderPort()
                    + " -> \"" + message + "\"");
        }
    }

    private static void runSender() throws Exception {
        System.out.println("[sender] Opening UDP socket on an ephemeral port...");
        try (UdpChannel channel = new UdpChannel()) {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            byte[] payload = "hello from sender".getBytes(StandardCharsets.UTF_8);
            System.out.println("[sender] Sending " + payload.length + " bytes to "
                    + loopback + ":" + PORT + " from local port " + channel.getLocalPort());
            channel.send(payload, loopback, PORT);
            System.out.println("[sender] Sent. Exiting.");
        }
    }
}
