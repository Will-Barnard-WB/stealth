package com.example.dup.text;

/** Small helper duplicated in ValueCheck: below the duplication threshold. */
public final class NameCheck {

    private NameCheck() {}

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
