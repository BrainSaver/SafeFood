package com.safefood.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;

public final class InviteCode {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final int ADDRESS_LENGTH = 10;

    private static final int SECRET_LENGTH = 4;

    public static final int LENGTH = ADDRESS_LENGTH + SECRET_LENGTH;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String code;
    private final InetAddress host;
    private final int port;

    private InviteCode(String code, InetAddress host, int port) {
        this.code = code;
        this.host = host;
        this.port = port;
    }

    public static String issue(InetAddress host, int port) {
        byte[] ip = host.getAddress();
        if (ip.length != 4) {
            throw new IllegalArgumentException(
                    "초대 코드에는 IPv4 주소만 담을 수 있습니다: " + host.getHostAddress());
        }
        if (port < 1 || port > 0xFFFF) {
            throw new IllegalArgumentException("포트 범위를 벗어났습니다: " + port);
        }

        long payload = 0;
        for (byte octet : ip) {
            payload = (payload << 8) | (octet & 0xFF);
        }
        payload = (payload << 16) | port;
        long value = (checksum(payload) << 48) | payload;

        char[] chars = new char[ADDRESS_LENGTH];
        for (int i = ADDRESS_LENGTH - 1; i >= 0; i--) {
            chars[i] = ALPHABET.charAt((int) (value & 0x1F));
            value >>>= 5;
        }
        return new String(chars) + newSecret();
    }

    private static String newSecret() {
        StringBuilder secret = new StringBuilder(SECRET_LENGTH);
        for (int i = 0; i < SECRET_LENGTH; i++) {
            secret.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return secret.toString();
    }

    /**
     * @throws IllegalArgumentException 형식이 틀린 경우 — 메시지를 화면에 그대로 보여 주면 됩니다
     */
    public static InviteCode parse(String raw) {
        String code = normalize(raw);
        if (code.isEmpty()) {
            throw new IllegalArgumentException("초대 코드를 입력해 주세요.");
        }
        if (code.length() != LENGTH) {
            throw new IllegalArgumentException(
                    "초대 코드는 " + LENGTH + "자입니다. (지금 " + code.length() + "자)");
        }

        long value = 0;
        for (int i = 0; i < ADDRESS_LENGTH; i++) {
            int index = ALPHABET.indexOf(code.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException(
                        "초대 코드에 쓸 수 없는 글자가 있습니다: " + code.charAt(i));
            }
            value = (value << 5) | index;
        }
        for (int i = ADDRESS_LENGTH; i < LENGTH; i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) {
                throw new IllegalArgumentException(
                        "초대 코드에 쓸 수 없는 글자가 있습니다: " + code.charAt(i));
            }
        }

        long payload = value & 0xFFFF_FFFF_FFFFL;
        if ((value >>> 48) != checksum(payload)) {
            throw new IllegalArgumentException("잘못 입력된 글자가 있습니다. 초대 코드를 다시 확인해 주세요.");
        }

        int port = (int) (payload & 0xFFFF);
        byte[] ip = new byte[4];
        long ipBits = payload >>> 16;
        for (int i = 3; i >= 0; i--) {
            ip[i] = (byte) (ipBits & 0xFF);
            ipBits >>>= 8;
        }
        if (port < 1 || ip[0] == 0) {
            throw new IllegalArgumentException("초대 코드에 담긴 접속 주소가 올바르지 않습니다.");
        }

        try {
            return new InviteCode(code, InetAddress.getByAddress(ip), port);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("초대 코드에 담긴 접속 주소가 올바르지 않습니다.");
        }
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(LENGTH);
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toUpperCase(raw.charAt(i));
            if (c == '-' || c == '_' || Character.isWhitespace(c)) {
                continue;
            }
            if (c == 'O') {
                c = '0';
            } else if (c == 'I' || c == 'L') {
                c = '1';
            }
            normalized.append(c);
        }
        return normalized.toString();
    }

    public static String addressPart(String code) {
        String normalized = normalize(code);
        return normalized.length() <= ADDRESS_LENGTH
                ? normalized : normalized.substring(0, ADDRESS_LENGTH);
    }

    public static String format(String code) {
        String normalized = normalize(code);
        if (normalized.length() != LENGTH) {
            return normalized;
        }
        return normalized.substring(0, 5) + "-"
                + normalized.substring(5, ADDRESS_LENGTH) + "-"
                + normalized.substring(ADDRESS_LENGTH);
    }

    private static long checksum(long payload) {
        long sum = 0;
        for (int shift = 0; shift < 48; shift += 8) {
            sum += (payload >>> shift) & 0xFF;
        }
        return sum & 0b11;
    }

    public String code() {
        return code;
    }

    public String addressPart() {
        return code.substring(0, ADDRESS_LENGTH);
    }

    public String host() {
        return host.getHostAddress();
    }

    public int port() {
        return port;
    }

    public String address() {
        return host() + ":" + port;
    }

    @Override
    public String toString() {
        return format(code);
    }
}
