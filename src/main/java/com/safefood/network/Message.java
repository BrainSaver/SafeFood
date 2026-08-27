package com.safefood.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 소켓 프로토콜 메시지 — 한 줄이 한 메시지, {@code 타입|본문} 형식입니다. */
public final class Message {

    public enum Type {
        // 클라이언트 → 서버
        JOIN, INFO, READY, VOTE, CHAT, RETRY, EXIT,
        // 서버 → 클라이언트
        JOINED, CANDIDATES, VOTE_STATUS, RESULT, LEFT, ERROR, MERGED, CLOSED
    }

    private final Type type;
    private final List<String> body;

    private Message(Type type, List<String> body) {
        this.type = type;
        this.body = List.copyOf(body);
    }

    public static Message of(Type type, String... body) {
        return new Message(type, Arrays.asList(body));
    }

    public static Message of(Type type, List<String> body) {
        return new Message(type, body);
    }

    /** 모르는 타입이거나 빈 줄이면 null. */
    public static Message parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        // limit -1: "INFO||2|10000"처럼 비어 있는 칸도 자리를 지키게
        String[] parts = line.trim().split("\\|", -1);
        Type type;
        try {
            type = Type.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<String> body = new ArrayList<>(Arrays.asList(parts).subList(1, parts.length));
        return new Message(type, body);
    }

    public Type type() {
        return type;
    }

    public List<String> body() {
        return body;
    }

    /** index번째 본문 칸. 없으면 빈 문자열. */
    public String part(int index) {
        return index < body.size() ? body.get(index) : "";
    }

    /** index부터 끝까지를 다시 |로 이어 붙입니다 — 본문에 |가 들어갈 수 있는 칸용. */
    public String rest(int index) {
        if (index >= body.size()) {
            return "";
        }
        return String.join("|", body.subList(index, body.size()));
    }

    public String serialize() {
        return body.isEmpty() ? type.name() : type.name() + "|" + String.join("|", body);
    }

    @Override
    public String toString() {
        return serialize();
    }
}
