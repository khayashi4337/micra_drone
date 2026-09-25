package io.github.khayashi4337.micradrone.build.plan;

import java.util.regex.Pattern;

/**
 * The form of the ids of nodes and connections: one rule for the patcher, which checks what is written into a plan,
 * and for the expander, which checks the ids inside the templates it reads.
 */
public final class PlanIds {
    /** The longest id, in characters. */
    public static final int MAX_LENGTH = 48;

    private static final Pattern VALID = Pattern.compile("[a-z0-9-]{1," + MAX_LENGTH + "}");

    private PlanIds() {
    }

    /** True for 1 to {@link #MAX_LENGTH} characters of lowercase letters, digits and hyphens. */
    public static boolean isValid(String id) {
        return VALID.matcher(id).matches();
    }

    /** The text of E-ID-INVALID for an id that is not {@link #isValid}. */
    public static String invalidMessage(String id) {
        return "IDは半角の小文字・数字・ハイフンで" + MAX_LENGTH + "字以内にしてください: " + id;
    }
}
