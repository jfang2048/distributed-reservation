package reservation;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class FrameFields {
    static final int MAX_FRAME = 1024 * 1024;
    static final int VERSION = 1;

    private FrameFields() {
    }

    static void writeText(DataOutputStream out, String text) throws IOException {
        if (text.length() > MAX_FRAME) {
            throw new IOException("Text field exceeds the frame limit");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
        int length = encoded.remaining();
        if (length > MAX_FRAME - out.size() - Integer.BYTES) {
            throw new IOException("Frame exceeds the size limit");
        }
        out.writeInt(length);
        out.write(encoded.array(), encoded.arrayOffset() + encoded.position(), length);
    }

    static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) {
            throw new IOException("Invalid text length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException problem) {
            throw new IOException("Invalid UTF-8 field", problem);
        }
    }

    static void writeId(DataOutputStream out, TxId id) throws IOException {
        writeText(out, id.coordinator());
        writeText(out, id.uuid().toString());
    }

    static TxId readId(DataInputStream in) throws IOException {
        String coordinator = readText(in);
        String text = readText(in);
        try {
            UUID uuid = UUID.fromString(text);
            if (!uuid.toString().equalsIgnoreCase(text)) {
                throw new IllegalArgumentException("Invalid UUID format");
            }
            return new TxId(coordinator, uuid);
        } catch (IllegalArgumentException problem) {
            throw new IOException("Invalid transaction key", problem);
        }
    }

    static void writeItems(DataOutputStream out, List<String> items) throws IOException {
        checkItems(items);
        out.writeInt(items.size());
        for (String item : items) {
            writeText(out, item);
        }
    }

    static List<String> readItems(DataInputStream in) throws IOException {
        int count = readCount(in, Integer.BYTES + 1);
        List<String> items = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            items.add(readText(in));
        }
        checkItems(items);
        return items;
    }

    static void writeParticipants(DataOutputStream out, List<Endpoint> participants) throws IOException {
        checkParticipants(participants);
        out.writeInt(participants.size());
        for (Endpoint server : participants) {
            writeText(out, server.id());
            writeText(out, server.host());
            out.writeInt(server.port());
        }
    }

    static List<Endpoint> readParticipants(DataInputStream in) throws IOException {
        int count = readCount(in, 3 * Integer.BYTES + 2);
        List<Endpoint> participants = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String id = readText(in);
            String host = readText(in);
            int port = in.readInt();
            try {
                participants.add(new Endpoint(id, host, port));
            } catch (IllegalArgumentException problem) {
                throw new IOException("Invalid participant address", problem);
            }
        }
        checkParticipants(participants);
        return participants;
    }

    private static int readCount(DataInputStream in, int minimumEntrySize) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > in.available() / minimumEntrySize) {
            throw new IOException("Invalid list length: " + count);
        }
        return count;
    }

    private static void checkItems(List<String> items) throws IOException {
        Set<String> seen = new HashSet<>();
        for (String item : items) {
            if (item.isBlank() || !seen.add(item)) {
                throw new IOException("Invalid or repeated item ID");
            }
        }
    }

    private static void checkParticipants(List<Endpoint> participants) throws IOException {
        Set<String> seen = new HashSet<>();
        for (Endpoint endpoint : participants) {
            if (!seen.add(endpoint.id())) {
                throw new IOException("Repeated participant ID");
            }
        }
    }
}
