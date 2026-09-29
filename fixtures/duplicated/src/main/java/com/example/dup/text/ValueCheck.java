package com.example.dup.text;

/** Small helper duplicated in NameCheck: below the duplication threshold. */
public final class ValueCheck {

    private ValueCheck() {}

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
