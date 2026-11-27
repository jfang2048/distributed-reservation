package reservation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

final class Wire {
    private Wire() {
    }

    static Message read(InputStream source) throws IOException {
        // TCP carries a byte stream. Read one bounded frame in full so field decoding cannot
        // consume bytes from a following frame.
        DataInputStream stream = new DataInputStream(source);
        int length = stream.readInt();
        if (length < 2 || length > FrameFields.MAX_FRAME) {
            throw new IOException("Invalid message frame length: " + length);
        }
        byte[] payload = new byte[length];
        stream.readFully(payload);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readUnsignedByte() != FrameFields.VERSION) {
            throw new IOException("Unsupported message version");
        }
        Type type = Type.decode(in.readUnsignedByte());
        TxId id = FrameFields.readId(in);
        String sender = type.nodeMessage() ? FrameFields.readText(in) : "";
        List<Endpoint> participants = hasParticipants(type) ? FrameFields.readParticipants(in) : List.of();
        List<String> items = hasItems(type) ? FrameFields.readItems(in) : List.of();
        String status = hasStatus(type) ? FrameFields.readText(in) : "";
        if (in.available() != 0) {
            throw new IOException("Trailing bytes in message");
        }
        Message message = new Message(type, id, sender, participants, items, status);
        validate(message);
        return message;
    }

    static void write(OutputStream destination, Message message) throws IOException {
        ByteArrayOutputStream payload = encode(message);
        DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(destination));
        stream.writeInt(payload.size());
        payload.writeTo(stream);
        stream.flush();
    }

    static void check(Message message) throws IOException {
        // Run the same validation and size checks used by write without opening a connection.
        encode(message);
    }

    private static ByteArrayOutputStream encode(Message message) throws IOException {
        validate(message);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        out.writeByte(FrameFields.VERSION);
        out.writeByte(message.type().code);
        FrameFields.writeId(out, message.id());
        if (message.type().nodeMessage()) {
            FrameFields.writeText(out, message.sender());
        }
        if (hasParticipants(message.type())) {
            FrameFields.writeParticipants(out, message.participants());
        }
        if (hasItems(message.type())) {
            FrameFields.writeItems(out, message.items());
        }
        if (hasStatus(message.type())) {
            FrameFields.writeText(out, message.status());
        }

        if (payload.size() > FrameFields.MAX_FRAME) {
            throw new IOException("Message frame exceeds the size limit");
        }
        return payload;
    }

    private static boolean hasParticipants(Type type) {
        return type == Type.PREPARE || type == Type.DECISION_REQUEST;
    }

    private static boolean hasItems(Type type) {
        return type == Type.PREPARE || type == Type.RESERVE;
    }

    private static boolean hasStatus(Type type) {
        return type == Type.RESULT || type == Type.ACK || type == Type.DECISION_REPLY;
    }

    private static void validate(Message message) throws IOException {
        Type type = message.type();
        boolean senderValid = type.nodeMessage() ? !message.sender().isBlank() : message.sender().isEmpty();
        boolean actualParticipants = !message.participants().isEmpty();
        boolean actualItems = !message.items().isEmpty();
        if (!senderValid || hasParticipants(type) != actualParticipants
                || hasItems(type) != actualItems
                || !validStatus(type, message.status())) {
            throw new IOException("Invalid fields for " + type);
        }
    }

    private static boolean validStatus(Type type, String status) {
        return switch (type) {
            case RESULT -> status.equals("COMMITTED") || status.equals("ABORTED")
                    || status.equals("PENDING") || status.equals("ID_CONFLICT") || status.equals("INVALID_REQUEST");
            case ACK -> status.equals("COMMITTED") || status.equals("ABORTED");
            case DECISION_REPLY -> status.equals("COMMITTED") || status.equals("ABORTED")
                    || status.equals("VOTE_ABORT") || status.equals("PENDING");
            default -> status.isEmpty();
        };
    }
}
