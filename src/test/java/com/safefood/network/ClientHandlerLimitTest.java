package com.safefood.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 끝없이 긴 줄로 방장 앱의 힙을 말리는 시도를 막는지 확인합니다.
 *
 * <p>DB는 타지 않습니다 — 입장 전에 끊기는지만 보므로 방의 추천 조회까지 가지 않습니다.
 */
@DisplayName("ClientHandler — 한 줄 길이 상한")
class ClientHandlerLimitTest {

    private static final String CODE = "TESTR00M99";
    private static final long TIMEOUT_MS = 3_000;

    private ServerSocket serverSocket;
    private Socket serverSide;
    private Socket clientSide;

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
    }

    @AfterEach
    void tearDown() {
        closeQuietly(clientSide);
        closeQuietly(serverSide);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    /** 손님 하나를 붙이고 서버 쪽 핸들러를 띄웁니다. */
    private BufferedWriter connect() throws IOException {
        clientSide = new Socket("localhost", serverSocket.getLocalPort());
        serverSide = serverSocket.accept();

        Thread handler = new Thread(
                new ClientHandler(serverSide, new Room(CODE), new JoinThrottle()), "limit-test");
        handler.setDaemon(true);
        handler.start();

        return new BufferedWriter(
                new OutputStreamWriter(clientSide.getOutputStream(), StandardCharsets.UTF_8));
    }

    private BufferedReader reader() throws IOException {
        return new BufferedReader(
                new InputStreamReader(clientSide.getInputStream(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("개행 없이 아주 긴 줄을 보내면 연결을 끊는다")
    void oversizedLineClosesConnection() throws IOException {
        BufferedWriter out = connect();
        clientSide.setSoTimeout((int) TIMEOUT_MS);
        BufferedReader in = reader();

        // 개행을 한 번도 보내지 않고 상한을 훌쩍 넘겨 밀어 넣습니다.
        // 끊긴 신호는 두 가지로 옵니다 — 깨끗한 EOF(null) 이거나, 아직 쓰는 중이었다면
        // 연결 재설정(SocketException). 둘 다 "서버가 잘랐다"는 같은 결과입니다.
        boolean cut = false;
        String chunk = "A".repeat(4096);
        try {
            for (int i = 0; i < 64; i++) {      // 256KB — 상한 8KB 를 크게 넘습니다
                out.write(chunk);
                out.flush();
            }
            cut = in.readLine() == null;
        } catch (IOException reset) {
            cut = true;
        }

        assertTrue(cut, "상한을 넘긴 연결은 끊겨야 한다");
    }

    @Test
    @DisplayName("정상 길이의 줄은 그대로 처리한다")
    void normalLineStillWorks() throws IOException {
        BufferedWriter out = connect();
        clientSide.setSoTimeout((int) TIMEOUT_MS);
        BufferedReader in = reader();

        // 틀린 코드로 입장을 시도하면 ERROR 를 받고 끊깁니다 — 줄이 정상 처리됐다는 뜻입니다
        out.write("JOIN|WRONGCODE0|민수");
        out.newLine();
        out.flush();

        Message reply = Message.parse(in.readLine());
        assertEquals(Message.Type.ERROR, reply.type());
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
