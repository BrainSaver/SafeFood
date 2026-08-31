package com.safefood.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class GroupClient implements Closeable {
    public interface Listener {
        void onMessage(Message message);

        void onDisconnected();
    }

    private static final int CONNECT_TIMEOUT_MS = 3_000;

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;
    private volatile Listener listener;
    private volatile boolean closedByMe;
    private volatile boolean disconnected;

    private GroupClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public static GroupClient connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        GroupClient client = new GroupClient(socket);
        Thread receiver = new Thread(client::receiveLoop, "group-client-recv");
        receiver.setDaemon(true);
        receiver.start();
        return client;
    }

    private void receiveLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                Message message = Message.parse(line);
                Listener current = listener;
                if (message != null && current != null) {
                    current.onMessage(message);
                }
            }
        } catch (IOException e) {
        }
        disconnected = true;
        Listener current = listener;
        if (!closedByMe && current != null) {
            current.onDisconnected();
        }
    }

    public boolean isAlive() {
        return !disconnected && !socket.isClosed();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void join(String inviteCode, String name) {
        send(Message.of(Message.Type.JOIN, inviteCode, name));
    }

    public void sendInfo(List<String> allergies, int spicyLevel, int budgetMax, int userId) {
        send(Message.of(Message.Type.INFO,
                String.join(",", allergies),
                String.valueOf(spicyLevel),
                String.valueOf(budgetMax),
                String.valueOf(userId)));
    }

    public void sendReady() {
        send(Message.of(Message.Type.READY));
    }

    public void sendVote(int candidateNo) {
        send(Message.of(Message.Type.VOTE, String.valueOf(candidateNo)));
    }

    public void sendChat(String text) {
        send(Message.of(Message.Type.CHAT, text));
    }

    public void sendRetry() {
        send(Message.of(Message.Type.RETRY));
    }

    /** 후보가 이미 있는데 마음에 들지 않을 때 — 이미 본 메뉴를 빼고 다시 뽑아 달라는 요청. */
    public void sendReroll() {
        send(Message.of(Message.Type.REROLL));
    }

    private void send(Message message) {
        synchronized (out) {
            try {
                out.write(message.serialize());
                out.newLine();
                out.flush();
            } catch (IOException e) {
                System.err.println("[client] 전송 실패: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        closedByMe = true;
        send(Message.of(Message.Type.EXIT));
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
