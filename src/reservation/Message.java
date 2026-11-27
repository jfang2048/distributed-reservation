package reservation;

import java.util.List;
import java.util.Objects;

record Message(Type type, TxId id, String sender, List<Endpoint> participants,
               List<String> items, String status) {
    Message {
        Objects.requireNonNull(type);
        Objects.requireNonNull(id);
        Objects.requireNonNull(sender);
        Objects.requireNonNull(status);
        participants = List.copyOf(participants);
        items = List.copyOf(items);
    }

    static Message simple(Type type, TxId id, String sender) {
        return new Message(type, id, sender, List.of(), List.of(), "");
    }

    static Message reply(Type type, TxId id, String sender, String status) {
        return new Message(type, id, sender, List.of(), List.of(), status);
    }
}
