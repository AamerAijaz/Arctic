package dev.arctic.polarion;

import java.util.regex.Pattern;

/** Removes bearer tokens from text that might be logged or returned. */
public final class Redaction {

    private static final Pattern BEARER = Pattern.compile("Bearer\\s+\\S+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final String REDACTED = "[redacted]";

    private Redaction() {
    }

    /** Replaces every {@code Bearer <value>}, then every literal occurrence of {@code token} if it is non-empty. */
    public static String redact(String text, String token) {
        String cleaned = BEARER.matcher(text).replaceAll("Bearer " + REDACTED);
        if (token != null && !token.isEmpty()) {
            cleaned = cleaned.replace(token, REDACTED);
        }
        return cleaned;
    }
}
