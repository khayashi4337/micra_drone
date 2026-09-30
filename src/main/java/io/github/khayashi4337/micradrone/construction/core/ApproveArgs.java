package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The trailing words of {@code /micradrone build approve <hash>} (Task 17, F-3): {@code confirm-terraform} and
 * {@code confirm-destructive} tick the boxes of {@link Confirmations}, and {@code accept=<issueId>[,<issueId>...]}
 * names the warnings the approver accepts, by exact issue id (01, section 11.1). An unknown word is an error, so a
 * typo can never look like a confirmation.
 */
public final class ApproveArgs {
    private static final String FLAG_TERRAFORM = "confirm-terraform";
    private static final String FLAG_DESTRUCTIVE = "confirm-destructive";
    private static final String FLAG_ACCEPT = "accept=";
    private static final String WORD_SEPARATOR = "\\s+";
    private static final String ID_SEPARATOR = ",";

    private ApproveArgs() {
    }

    /** The parsed flags: {@code error} is null on success and explains the first unknown word otherwise. */
    public record Parsed(Confirmations confirmations, List<AcceptedRisk> risks, String error) {
    }

    public static Parsed parse(String flags) {
        boolean terraform = false;
        boolean destructive = false;
        List<AcceptedRisk> risks = new ArrayList<>();
        String invalid = null;
        for (String word : flags.trim().split(WORD_SEPARATOR)) {
            if (word.isEmpty()) {
                continue;
            }
            if (FLAG_TERRAFORM.equals(word)) {
                terraform = true;
            } else if (FLAG_DESTRUCTIVE.equals(word)) {
                destructive = true;
            } else if (word.startsWith(FLAG_ACCEPT)) {
                for (String id : word.substring(FLAG_ACCEPT.length()).split(ID_SEPARATOR)) {
                    if (!id.isEmpty()) {
                        risks.add(new AcceptedRisk(id, ""));
                    }
                }
            } else if (invalid == null) {
                invalid = "unknown approve flag: " + word;
            }
        }
        if (invalid != null) {
            return new Parsed(Confirmations.NONE, List.of(), invalid);
        }
        return new Parsed(new Confirmations(terraform, destructive), List.copyOf(risks), null);
    }
}
