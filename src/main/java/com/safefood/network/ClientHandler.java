package com.safefood.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClientHandler implements Runnable {

    private static final int JOIN_TIMEOUT_MS = 10_000;
    private static final int OUTBOX_LIMIT = 256;
    private static final Message POISON = Message.of(Message.Type.EXIT);

    private final Socket socket;
    private final Room room;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final BlockingQueue<Message> outbox = new ArrayBlockingQueue<>(OUTBOX_LIMIT);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Thread writer;
    private boolean joined;

    ClientHandler(Socket socket, Room room) throws IOException {
        this.socket = socket;
        this.room = room;
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        this.writer = new Thread(this::writeLoop, "group-client-writer");
        this.writer.setDaemon(true);
    }

    @Override
    public void run() {
        writer.start();
        try {
            socket.setSoTimeout(JOIN_TIMEOUT_MS);
            String line;
            while ((line = in.readLine()) != null) {
                Message message = Message.parse(line);
                if (message == null) {
                    continue;
                }
                if (!joined) {
                    if (message.type() != Message.Type.JOIN) {
                        send(Message.of(Message.Type.ERROR, "먼저 JOIN|초대코드|이름 을 보내야 합니다."));
                        return;
                    }
                    joined = room.join(this, message.part(0), message.part(1).trim());
                    if (!joined) {
                        return;
                    }
                    socket.setSoTimeout(0);
                    continue;
                }
                switch (message.type()) {
                    case INFO -> room.updateInfo(this,
                            splitCsv(message.part(0)),
                            parseInt(message.part(1), 5),
                            parseInt(message.part(2), 0),
                            parseInt(message.part(3), 0));
                    case READY -> room.markReady(this);
                    case VOTE -> room.vote(this, parseInt(message.part(0), 0));
                    case CHAT -> room.chat(this, message.rest(0));
                    case RETRY -> room.retry(this);
                    case EXIT -> {
                        return;
                    }
                    default -> { }
                }
            }
        } catch (IOException e) {
            // 창을 그냥 닫은 경우 등 — finally의 leave()가 LEFT로 처리합니다
        } finally {
            if (joined) {
                room.leave(this);
            }
            close();
        }
    }

    void send(Message message) {
        if (closing.get()) {
            return;
        }
        if (!outbox.offer(message)) {
            close();
        }
    }

    void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        if (!outbox.offer(POISON)) {
            outbox.clear();
            outbox.offer(POISON);
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                Message message = outbox.take();
                if (message == POISON) {
                    return;
                }
                out.write(message.serialize());
                out.newLine();
                out.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException broken) {
            closing.set(true);
        } finally {
            closeSocket();
        }
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static List<String> splitCsv(String csv) {
        List<String> values = new ArrayList<>();
        for (String token : csv.split(",")) {
            if (!token.isBlank()) {
                values.add(token.trim());
            }
        }
        return values;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
