package com.safefood.dto;

/**
 * 알레르기 안전 등급
 */
public enum Safety {

    SAFE("SAFE (알레르기 안전)", "✓ 알레르기 안전", "safe"),

    POSSIBLE("POSSIBLE (혼입 가능)", "⚠ 혼입 가능", "possible"),

    CONTAINS("CONTAINS (알레르기 원료 포함)", "✕ 원료 포함", "contains");

    public final String label;
    public final String shortLabel;
    public final String styleClass;

    Safety(String label, String shortLabel, String styleClass) {
        this.label = label;
        this.shortLabel = shortLabel;
        this.styleClass = styleClass;
    }
}
