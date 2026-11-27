package reservation;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

public final class Main {
    private static final Path CONFIG = Path.of("config", "cluster.properties");
    private static final String USAGE = "Commands: server --node ID | reserve [--coordinator ID] [--tx-id UUID] ITEM...";

    private Main() {}

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println(USAGE);
            return;
        }
        try {
            run(args);
        } catch (NoSuchFileException missing) {
            System.err.println("File missing: " + missing.getFile());
            System.exit(1);
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted.");
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException, InterruptedException {
        if (args.length == 0) {
            throw new IllegalArgumentException(USAGE);
        }
        switch (args[0]) {
            case "server" -> {
                if (args.length != 3 || !args[1].equals("--node")) {
                    throw new IllegalArgumentException("Usage: server --node ID");
                }
                ClusterConfig config = ClusterConfig.load(CONFIG);
                new Server(config, args[2]).run();
            }
            case "reserve" -> Client.run(ClusterConfig.load(CONFIG), Arrays.copyOfRange(args, 1, args.length));
            default -> throw new IllegalArgumentException(USAGE);
        }
    }
}
