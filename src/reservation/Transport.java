package reservation;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class Transport {
    private static final int WORKERS = 16;
    private static final int BACKLOG = 64;
    private final ClusterConfig config;

    Transport(ClusterConfig config) {
        this.config = config;
    }

    Message call(Endpoint target, Message request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), config.connectTimeoutMs());
            socket.setSoTimeout(config.readTimeoutMs());
            Wire.write(socket.getOutputStream(), request);
            return Wire.read(socket.getInputStream());
        }
    }

    void serve(String node, Function<Message, Message> handler) throws IOException {
        // Bound the number of queued connections. Close a socket immediately when the worker queue is full.
        ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKERS, WORKERS, 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(BACKLOG));
        try (ServerSocket listener = new ServerSocket()) {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(config.bind(node), config.listenPort(node)), BACKLOG);
            System.out.printf("LISTENING %s %s%n", node, listener.getLocalSocketAddress());
            while (true) {
                Socket socket = listener.accept();
                try {
                    workers.execute(() -> handle(socket, handler));
                } catch (RejectedExecutionException busy) {
                    socket.close();
                }
            }
        } finally {
            workers.shutdown();
        }
    }

    private void handle(Socket socket, Function<Message, Message> handler) {
        try (socket) {
            socket.setSoTimeout(config.readTimeoutMs());
            Message request = Wire.read(socket.getInputStream());
            Message reply = handler.apply(request);
            if (reply != null) {
                Wire.write(socket.getOutputStream(), reply);
            }
        } catch (IOException peerDisconnected) {
            // An invalid frame, failed read, or failed reply ends this connection.
        }
    }
}
