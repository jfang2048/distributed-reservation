package reservation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class UnitTests {
    private UnitTests() {
    }

    public static void main(String[] args) throws IOException {
        wireFrames();
        logReplay();
        resourceClaims();
        System.out.println("Wire, log, and resource unit tests passed");
    }

    private static void wireFrames() throws IOException {
        List<Endpoint> peers = List.of(new Endpoint("b", "localhost", 7002));
        Message prepare = new Message(Type.PREPARE, transaction(1), "a", peers,
                List.of("écran"), "");
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        Wire.write(encoded, prepare);
        byte[] frame = encoded.toByteArray();
        check(decode(frame).equals(prepare), "UTF-8 prepare round trip");

        byte[] truncated = Arrays.copyOf(frame, frame.length - 1);
        expectIOException(() -> decode(truncated), "truncated frame");

        byte[] malformed = frame.clone();
        malformed[5] = 0;
        expectIOException(() -> decode(malformed), "unknown message type");

        byte[] invalidText = frame.clone();
        invalidText[invalidText.length - 1] = (byte) 0xff;
        expectIOException(() -> decode(invalidText), "invalid UTF-8");
    }

    private static Message decode(byte[] frame) throws IOException {
        return Wire.read(new ByteArrayInputStream(frame));
    }

    private static void logReplay() throws IOException {
        Path directory = Files.createTempDirectory("reservation-wal-test-");
        Path file = directory.resolve("transactions.wal");
        TxId id = transaction(1);
        var start = new TransactionLog.Entry(TransactionLog.Role.COORDINATOR,
                TransactionLog.Kind.START_2PC, id, List.of(new Endpoint("a", "localhost", 7001)),
                List.of("projector"));
        var abort = new TransactionLog.Entry(TransactionLog.Role.COORDINATOR,
                TransactionLog.Kind.GLOBAL_ABORT, id, List.of(), List.of());
        try {
            long firstRecordSize;
            try (TransactionLog log = new TransactionLog(directory)) {
                log.append(start);
                firstRecordSize = Files.size(file);
                log.append(abort);
                expectIOException(() -> new TransactionLog(directory).close(), "duplicate open");
            }

            byte[] complete = Files.readAllBytes(file);
            Files.write(file, Arrays.copyOf(complete, complete.length - 2));
            try (TransactionLog log = new TransactionLog(directory)) {
                check(log.replay().equals(List.of(start)), "replay the complete prefix");
                check(Files.size(file) == firstRecordSize, "remove exactly the partial tail");
                log.append(abort);
                check(log.replay().equals(List.of(start, abort)), "append after tail recovery");
            }

            complete[complete.length - 1] ^= 1;
            Files.write(file, complete);
            try (TransactionLog log = new TransactionLog(directory)) {
                expectIOException(log::replay, "CRC mismatch on a complete record");
            }
            check(Arrays.equals(Files.readAllBytes(file), complete), "preserve corrupted complete record");
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }

    private static void resourceClaims() {
        ResourceManager resources = new ResourceManager();
        TxId first = transaction(1);
        TxId second = transaction(2);
        TxId third = transaction(3);
        List<String> camera = List.of("camera");
        List<String> projector = List.of("projector");
        check(resources.claim(first, camera), "claim camera");
        check(!resources.claim(second, List.of("projector", "camera")), "reject a held camera");
        check(resources.claim(third, projector), "failed claim leaves the projector free");
        resources.finish(first, camera, false);
        check(resources.claim(second, camera), "abort releases the camera");
        resources.finish(second, camera, true);
        check(!resources.claim(third, camera), "commit keeps the camera reserved");
    }

    private static TxId transaction(long number) {
        return new TxId("a", new UUID(0, number));
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }

    private static void expectIOException(IOAction action, String name) {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected IOException: " + name);
    }

    @FunctionalInterface
    private interface IOAction {
        void run() throws IOException;
    }
}
