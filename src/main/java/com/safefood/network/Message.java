package com.safefood.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Message {
    public enum Type {
        JOIN, INFO, READY, VOTE, CHAT, RETRY, REROLL, EXIT,
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

    public static Message parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
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

    public String part(int index) {
        return index < body.size() ? body.get(index) : "";
    }

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
