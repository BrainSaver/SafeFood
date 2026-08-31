package com.safefood.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
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

    /**
     * 한 줄의 최대 길이. 프로토콜에서 가장 긴 줄(후보 3개 CSV)도 1KB 를 넘지 않으므로 넉넉합니다.
     *
     * <p>{@code BufferedReader.readLine()} 은 개행이 나올 때까지 <b>끝없이</b> 모읍니다.
     * 개행 없이 계속 보내기만 하면 방장 앱의 힙이 바닥납니다 — 그 전에 끊습니다.
     */
    private static final int MAX_LINE_LENGTH = 8 * 1024;

    private final Socket socket;
    private final Room room;
    private final JoinThrottle throttle;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final BlockingQueue<Message> outbox = new ArrayBlockingQueue<>(OUTBOX_LIMIT);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Thread writer;
    private boolean joined;

    ClientHandler(Socket socket, Room room, JoinThrottle throttle) throws IOException {
        this.socket = socket;
        this.room = room;
        this.throttle = throttle;
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
            while ((line = readLine()) != null) {
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
                    throttle.record(remoteAddress(), joined);
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
                    case REROLL -> room.reroll(this);
                    case EXIT -> {
                        return;
                    }
                    default -> { }
                }
            }
        } catch (IOException e) {
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

    /**
     * 길이를 제한해 한 줄을 읽습니다. 상한을 넘으면 그 연결을 포기합니다 (null).
     *
     * @return 한 줄, 또는 스트림이 끝났거나 줄이 너무 길면 null
     */
    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return line.toString();
            }
            if (c == '\r') {
                continue;
            }
            if (line.length() >= MAX_LINE_LENGTH) {
                System.err.println("[server] 한 줄이 " + MAX_LINE_LENGTH
                        + "자를 넘어 연결을 끊습니다: " + remoteAddress());
                return null;
            }
            line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private InetAddress remoteAddress() {
        return socket.getInetAddress();
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
