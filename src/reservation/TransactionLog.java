package reservation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

final class TransactionLog implements AutoCloseable {
    private static final Set<Path> OPEN_LOGS = ConcurrentHashMap.newKeySet();

    enum Role {
        COORDINATOR(1), PARTICIPANT(2);

        final int code;

        Role(int code) {
            this.code = code;
        }

        static Role decode(int code) throws IOException {
            for (Role role : values()) {
                if (role.code == code) {
                    return role;
                }
            }
            throw new IOException("Unknown log role: " + code);
        }
    }

    enum Kind {
        START_2PC(1), INIT(2), VOTE_COMMIT(3), VOTE_ABORT(4),
        GLOBAL_COMMIT(5), GLOBAL_ABORT(6), END(7);

        final int code;

        Kind(int code) {
            this.code = code;
        }

        static Kind decode(int code) throws IOException {
            for (Kind kind : values()) {
                if (kind.code == code) {
                    return kind;
                }
            }
            throw new IOException("Unknown log record type: " + code);
        }
    }

    record Entry(Role role, Kind kind, TxId id, List<Endpoint> participants, List<String> items) {
        Entry {
            Objects.requireNonNull(role);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(id);
            participants = List.copyOf(participants);
            items = List.copyOf(items);
        }
    }

    private final Path path;
    private final FileLock lock;
    private boolean readyToAppend;
    private boolean closed;

    TransactionLog(Path directory) throws IOException {
        Files.createDirectories(directory);
        path = directory.toRealPath().resolve("transactions.wal");
        if (!OPEN_LOGS.add(path)) {
            throw new IOException("Transaction log is already open: " + path);
        }
        FileLock opened = null;
        try {
            opened = openLocked(path);
            readyToAppend = opened.channel().size() == 0;
            lock = opened;
        } catch (IOException | RuntimeException problem) {
            if (opened != null) {
                closeAfterFailure(opened.channel(), problem);
            }
            OPEN_LOGS.remove(path);
            throw problem;
        }
    }

    private static FileLock openLocked(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, CREATE, READ, WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException problem) {
                throw new IOException("Transaction log is already open: " + path, problem);
            }
            if (lock == null) {
                throw new IOException("Transaction log is already open: " + path);
            }
            return lock;
        } catch (IOException | RuntimeException problem) {
            closeAfterFailure(channel, problem);
            throw problem;
        }
    }

    private static void closeAfterFailure(FileChannel channel, Throwable problem) {
        try {
            channel.close();
        } catch (IOException closing) {
            problem.addSuppressed(closing);
        }
    }

    synchronized List<Entry> replay() throws IOException {
        readyToAppend = false;
        FileChannel channel = lock.channel();
        channel.position(0);
        long size = channel.size();
        List<Entry> entries = new ArrayList<>();
        ByteBuffer lengthField = ByteBuffer.allocate(Integer.BYTES);
        long offset = 0;
        while (offset < size) {
            long remaining = size - offset;
            if (remaining < Integer.BYTES) {
                truncateTail(channel, offset);
                break;
            }
            readFully(channel, lengthField);
            int length = lengthField.getInt();
            if (length < 1 || length > FrameFields.MAX_FRAME) {
                throw new IOException("Invalid log length at byte " + offset + ": " + length);
            }

            int frameSize = Integer.BYTES + length + Integer.BYTES;
            if (remaining < frameSize) {
                truncateTail(channel, offset);
                break;
            }
            ByteBuffer body = ByteBuffer.allocate(length + Integer.BYTES);
            readFully(channel, body);
            CRC32 checksum = new CRC32();
            checksum.update(lengthField.array());
            checksum.update(body.array(), 0, length);
            if ((int) checksum.getValue() != body.getInt(length)) {
                throw new IOException("Log checksum mismatch at byte " + offset);
            }
            try {
                entries.add(decode(body.array(), length));
            } catch (IOException problem) {
                throw new IOException("Invalid log record at byte " + offset, problem);
            }
            offset += frameSize;
        }
        readyToAppend = true;
        return List.copyOf(entries);
    }

    synchronized void append(Entry entry) throws IOException {
        if (!readyToAppend) {
            throw new IOException("Replay the transaction log before appending");
        }
        byte[] payload = encode(entry);
        ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES + payload.length + Integer.BYTES);
        frame.putInt(payload.length);
        frame.put(payload);
        CRC32 checksum = new CRC32();
        checksum.update(frame.array(), 0, frame.position());
        frame.putInt((int) checksum.getValue());
        frame.flip();

        FileChannel channel = lock.channel();
        readyToAppend = false;
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
        channel.force(true);
        readyToAppend = true;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        readyToAppend = false;
        try {
            lock.channel().close();
        } finally {
            OPEN_LOGS.remove(path);
        }
    }

    private static void truncateTail(FileChannel channel, long offset) throws IOException {
        channel.truncate(offset);
        channel.position(offset);
        channel.force(true);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        buffer.clear();
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException("Transaction log changed during replay");
            }
        }
        buffer.flip();
    }

    private static byte[] encode(Entry entry) throws IOException {
        validate(entry);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(FrameFields.VERSION);
        out.writeByte(entry.role().code);
        out.writeByte(entry.kind().code);
        FrameFields.writeId(out, entry.id());
        FrameFields.writeParticipants(out, entry.participants());
        FrameFields.writeItems(out, entry.items());
        if (bytes.size() > FrameFields.MAX_FRAME) {
            throw new IOException("Log record exceeds the size limit");
        }
        return bytes.toByteArray();
    }

    static void check(Entry entry) throws IOException {
        encode(entry);
    }

    private static Entry decode(byte[] bytes, int length) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, length));
        if (in.readUnsignedByte() != FrameFields.VERSION) {
            throw new IOException("Unsupported log version");
        }
        Role role = Role.decode(in.readUnsignedByte());
        Kind kind = Kind.decode(in.readUnsignedByte());
        TxId id = FrameFields.readId(in);
        List<Endpoint> participants = FrameFields.readParticipants(in);
        List<String> items = FrameFields.readItems(in);
        if (in.available() != 0) {
            throw new IOException("Trailing bytes in log record");
        }
        Entry entry = new Entry(role, kind, id, participants, items);
        validate(entry);
        return entry;
    }

    private static void validate(Entry entry) throws IOException {
        boolean hasParticipants = !entry.participants().isEmpty();
        boolean hasItems = !entry.items().isEmpty();
        boolean valid = switch (entry.role()) {
            case COORDINATOR -> switch (entry.kind()) {
                case START_2PC -> hasItems;
                case GLOBAL_COMMIT, GLOBAL_ABORT, END -> !hasParticipants && !hasItems;
                default -> false;
            };
            case PARTICIPANT -> switch (entry.kind()) {
                case INIT, VOTE_COMMIT -> hasParticipants && hasItems;
                case VOTE_ABORT -> hasParticipants;
                case GLOBAL_COMMIT -> !hasParticipants && hasItems;
                case GLOBAL_ABORT -> !hasParticipants;
                default -> false;
            };
        };
        if (!valid) {
            throw new IOException("Invalid fields for " + entry.role() + " " + entry.kind());
        }
    }
}
