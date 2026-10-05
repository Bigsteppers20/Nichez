package app.nichez.common;

/** Redaction for anything that reaches a log line or the audit trail. */
public final class Masking {

    private static final int VISIBLE_PHONE_DIGITS = 4;

    private Masking() {
    }

    /**
     * {@code +2348031234567} becomes {@code +*********4567}. Keeps the plus and
     * the last four digits; anything unparseable is fully masked.
     */
    public static String phone(String e164) {
        if (e164 == null || e164.isBlank()) {
            return "<none>";
        }
        int length = e164.length();
        if (length <= VISIBLE_PHONE_DIGITS + 1 || e164.charAt(0) != '+') {
            return "*".repeat(Math.min(length, 12));
        }
        return "+" + "*".repeat(length - 1 - VISIBLE_PHONE_DIGITS) + e164.substring(length - VISIBLE_PHONE_DIGITS);
    }

    /** A code is never shown, not even partly. Use this when a log line must mention one. */
    public static String code() {
        return "******";
    }
}
