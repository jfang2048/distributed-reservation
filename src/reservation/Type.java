package reservation;

import java.io.IOException;

enum Type {
    // These explicit codes are part of the wire format and must remain stable across builds.
    RESERVE(1),
    RESULT(2),
    PREPARE(3),
    VOTE_COMMIT(4),
    VOTE_ABORT(5),
    GLOBAL_COMMIT(6),
    GLOBAL_ABORT(7),
    ACK(8),
    DECISION_REQUEST(9),
    DECISION_REPLY(10);

    final int code;

    Type(int code) {
        this.code = code;
    }

    static Type decode(int code) throws IOException {
        for (Type type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IOException("Unknown message type: " + code);
    }

    boolean nodeMessage() {
        return this != RESERVE && this != RESULT;
    }
}
