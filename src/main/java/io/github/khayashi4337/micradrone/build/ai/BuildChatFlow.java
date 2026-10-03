package io.github.khayashi4337.micradrone.build.ai;

import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The whole decision logic of the chat panel's "けんちく" (build) mode (P4 task M3), kept pure-Java
 * so every rule is unit-testable: the child asks in Japanese, the AI answers with a plan JSON, the
 * server answers with an offer, the child presses つくる, and progress lines arrive until the job
 * ends. The screen feeds events ({@link #request}, {@link #consent}, {@link #aiReply},
 * {@link #offer}, {@link #progress}, {@link #approve}, {@link #cancel}) and executes the returned
 * {@link Action}s - networking, translation and widgets all live on the adapter side.
 *
 * <p>Child-facing lines go out as {@link Say} actions carrying a {@code micradrone.build.chat.*}
 * translation key plus string args; {@code MessageKey} itself is not reused because it lives in
 * {@code construction.core}, which {@code build.*} must not import (BuildPurityTest). The keys are
 * registered in {@code ChildMessages}' fixed set so ChildMessagesTest keeps code, ja_jp and en_us
 * equal. The technical {@code message} text of offer issues is repair input for the AI only - it
 * never reaches a {@link Say}.
 */
public final class BuildChatFlow {
    /** How many times a rejected/failed plan is sent back to the AI for repair before giving up. */
    public static final int MAX_REPAIRS = 2;
    /** A1: this many ordinary AI failures in a row switches the retry line to "ask a grown-up". */
    private static final int AI_FAILED_ADULT_AFTER = 2;

    /** The flow's states; the screen mirrors this for the devkit's state probe. */
    public enum State { IDLE, NEED_CONSENT, ASKING_AI, WAITING_OFFER, OFFERED, BUILDING, DONE, FAILED,
        CONFIRM_UNDO, UNDOING }

    /** Which buttons the screen may show in the insert row; an empty {@link ShowButtons} hides them. */
    public enum ButtonKind { CONSENT_YES, CONSENT_NO, BUILD, CANCEL, UNDO, UNDO_YES, UNDO_NO,
        INVENTORY_YES, INVENTORY_NO }

    public sealed interface Action {
    }

    /** Run {@code prompt} through the {@link StageCliRunner}; the reply comes back via {@link #aiReply}. */
    public record AskAi(String prompt) implements Action {
    }

    /** Upload the extracted plan JSON to the server (one chunk). */
    public record SendPlan(String json) implements Action {
    }

    /** Approve the offered manifest hash; the two flags carry the MVP's confirm-on-press rule. */
    public record SendApprove(String hash, boolean confirmTerraform, boolean confirmDestructive)
            implements Action {
    }

    /** Cancel the running job. */
    public record SendCancel(String jobId) implements Action {
    }

    /** Ask the server to roll the claim back to its pre-build state (M5; sent only after UNDO_YES). */
    public record SendRollback(String claimId) implements Action {
    }

    /**
     * The claim's material rules (Task 27b): {@code allowed} is the new inventory switch or null to
     * leave it; {@code exclude}/{@code include} are the item ids to add to / take out of the claim's
     * ruled-out set. The claim id rides inside the packet, never a child-facing line.
     */
    public record SendMaterials(String claimId, Boolean allowed, List<String> exclude,
                                List<String> include) implements Action {
        public SendMaterials {
            Objects.requireNonNull(claimId);
            exclude = List.copyOf(exclude);
            include = List.copyOf(include);
        }
    }

    /** One child-facing line: a {@code micradrone.build.chat.*} key plus string args. */
    public record Say(String key, List<String> args) implements Action {
        public Say {
            Objects.requireNonNull(key);
            args = List.copyOf(args);
        }
    }

    /** Replace the buttons shown in the panel's action row; an empty list hides it. */
    public record ShowButtons(List<ButtonKind> buttons) implements Action {
        public ShowButtons {
            buttons = List.copyOf(buttons);
        }
    }

    // Child-facing line keys; the texts live in the lang files and ChildMessages.FIXED lists these.
    public static final String MSG_CONSENT = "micradrone.build.chat.consent";
    public static final String MSG_CLI_MISSING = "micradrone.build.chat.cli_missing";
    /** A1: the CLI answered but is not signed in - retrying can never fix it, so ask a grown-up. */
    public static final String MSG_LOGIN_MISSING = "micradrone.build.chat.login_missing";
    public static final String MSG_AI_FAILED = "micradrone.build.chat.ai_failed";
    /** A1: ordinary failures keep repeating - the child should fetch a grown-up instead of retrying. */
    public static final String MSG_AI_FAILED_ADULT = "micradrone.build.chat.ai_failed_adult";
    public static final String MSG_PLAN_FAILED = "micradrone.build.chat.plan_failed";
    /** The AI's own hiragana one-liner, passed through as the single arg. */
    public static final String MSG_SAYS = "micradrone.build.chat.says";
    public static final String MSG_OFFER = "micradrone.build.chat.offer";
    public static final String MSG_OFFER_TERRAIN = "micradrone.build.chat.offer_terrain";
    public static final String MSG_PLACE_IN_FRONT = "micradrone.build.chat.place_in_front";
    public static final String MSG_BUILDING = "micradrone.build.chat.building";
    public static final String MSG_DONE = "micradrone.build.chat.done";
    public static final String MSG_PARTIAL = "micradrone.build.chat.partial";
    public static final String MSG_BUSY = "micradrone.build.chat.busy";
    public static final String MSG_CANCELLED = "micradrone.build.chat.cancelled";
    /** The approve was sent but the server turned it down (M3b: no job ever started). */
    public static final String MSG_APPROVE_REFUSED = "micradrone.build.chat.approve_refused";
    /** M5: the undo question, progress, endings and refusal - none may carry an id (M2b). */
    public static final String MSG_UNDO_ASK = "micradrone.build.chat.undo_ask";
    public static final String MSG_UNDOING = "micradrone.build.chat.undoing";
    public static final String MSG_UNDO_DONE = "micradrone.build.chat.undo_done";
    public static final String MSG_UNDO_PARTIAL = "micradrone.build.chat.undo_partial";
    public static final String MSG_UNDO_REFUSED = "micradrone.build.chat.undo_refused";
    /**
     * Panel-only wording for the two pauses whose status-command text names an adult command and a
     * job id (pause.recovery_needed, pause.site_changed): the panel has no button for either, so the
     * child is only told to ask a grown-up.
     */
    public static final String MSG_PAUSE_RECOVERY = "micradrone.build.chat.pause_recovery";
    public static final String MSG_PAUSE_SITE_CHANGED = "micradrone.build.chat.pause_site_changed";
    /** Task 27b: the missing-materials question, its two answers, and the offer's source line. */
    public static final String MSG_ASK_INVENTORY = "micradrone.build.chat.ask_inventory";
    public static final String MSG_INVENTORY_ON = "micradrone.build.chat.inventory_on";
    public static final String MSG_INVENTORY_OFF = "micradrone.build.chat.inventory_off";
    public static final String MSG_OFFER_SOURCE = "micradrone.build.chat.offer_source";
    /**
     * A chat-voiced materials rule (27b追加): the confirmation line is ALWAYS said for an accepted
     * directive (the change must be visible to be trustworthy); excluded/included name one item id
     * each as their arg - the screen swaps in the item's display name - and unclear is the answer
     * to a directive that failed validation whole.
     */
    public static final String MSG_MATERIALS_CHANGED = "micradrone.build.chat.materials_changed";
    public static final String MSG_MATERIALS_EXCLUDED = "micradrone.build.chat.materials_excluded";
    public static final String MSG_MATERIALS_INCLUDED = "micradrone.build.chat.materials_included";
    public static final String MSG_MATERIALS_UNCLEAR = "micradrone.build.chat.materials_unclear";
    /**
     * The server answered a materials packet this flow sent with a bare REJECTED offer (Task 27e):
     * the child hears its request could not be taken - the build itself goes on.
     */
    public static final String MSG_MATERIALS_REFUSED = "micradrone.build.chat.materials_refused";

    /** Every child-facing key this flow can emit - ChildMessages registers all of them. */
    public static final Set<String> CHAT_MESSAGE_KEYS = Set.of(MSG_CONSENT, MSG_CLI_MISSING,
            MSG_LOGIN_MISSING, MSG_AI_FAILED, MSG_AI_FAILED_ADULT, MSG_PLAN_FAILED, MSG_SAYS,
            MSG_OFFER, MSG_OFFER_TERRAIN, MSG_PLACE_IN_FRONT,
            MSG_BUILDING, MSG_DONE, MSG_PARTIAL, MSG_BUSY, MSG_CANCELLED, MSG_APPROVE_REFUSED,
            MSG_UNDO_ASK, MSG_UNDOING, MSG_UNDO_DONE, MSG_UNDO_PARTIAL, MSG_UNDO_REFUSED,
            MSG_PAUSE_RECOVERY, MSG_PAUSE_SITE_CHANGED, MSG_ASK_INVENTORY, MSG_INVENTORY_ON,
            MSG_INVENTORY_OFF, MSG_OFFER_SOURCE, MSG_MATERIALS_CHANGED, MSG_MATERIALS_EXCLUDED,
            MSG_MATERIALS_INCLUDED, MSG_MATERIALS_UNCLEAR, MSG_MATERIALS_REFUSED);

    // The "state" values of the offer document (construction.core.OfferView writes these; the
    // strings are re-spelled here because build.* may not import construction.*).
    private static final String OFFER_OFFERED = "OFFERED";
    private static final String OFFER_REJECTED = "REJECTED";
    private static final String OFFER_FAILED = "FAILED";

    // The JobKind names ProgressView writes into each progress document's "kind" field (M5);
    // re-spelled because build.* may not import construction.core.
    private static final String KIND_BUILD = "BUILD";
    private static final String KIND_ROLLBACK = "ROLLBACK";
    private static final String STATE_ROLLED_BACK = "ROLLED_BACK";
    // Progress/offer doc field names re-spelled the same way (PauseReason/MaterialPolicy names).
    private static final String PAUSE_MATERIALS_MISSING = "MATERIALS_MISSING";
    private static final String POLICY_SURVIVAL = "SURVIVAL_CONSUME";

    /**
     * The pause lines the status command already shows ({@code ChildMessages.pause(PauseReason)}
     * builds the same keys); re-spelled here because build.* may not import construction.core. A
     * pause name outside this set has no child-facing text and stays silent.
     */
    private static final String PAUSE_KEY_PREFIX = "micradrone.build.pause.";
    private static final Set<String> PAUSE_KEYS = Set.of(
            PAUSE_KEY_PREFIX + "owner_offline", PAUSE_KEY_PREFIX + "chunk_unloaded",
            PAUSE_KEY_PREFIX + "materials_missing", PAUSE_KEY_PREFIX + "server_busy",
            PAUSE_KEY_PREFIX + "user", PAUSE_KEY_PREFIX + "no_room",
            PAUSE_KEY_PREFIX + "entity_in_way");
    /** Pause names that use the panel-only wording instead of the status command's pause text. */
    private static final Map<String, String> PANEL_PAUSE_KEYS = Map.of(
            PAUSE_KEY_PREFIX + "recovery_needed", MSG_PAUSE_RECOVERY,
            PAUSE_KEY_PREFIX + "site_changed", MSG_PAUSE_SITE_CHANGED);

    /** The three texts {@link BuildPromptBuilder} needs - gathered once by the screen. */
    public record PromptParts(String sampleJson, String partsCatalog, String allowedBlocks) {
    }

    private final PromptParts parts;
    /** The item catalog, as a predicate - build.* may not import construction.core (BuildPurityTest). */
    private final Predicate<String> knownItem;
    /**
     * The game's language code supplier (L1): read per AI call, so a language switch mid-play
     * applies from the next request/repair prompt rather than needing a new flow.
     */
    private final Supplier<String> languageCode;
    /**
     * A1: the panel's {@code claude --version} probe, as a tri-state (null = still unknown,
     * TRUE = installed, FALSE = known absent). Read per request, so a CLI a grown-up installs
     * later is picked up without rebuilding the flow.
     */
    private final Supplier<Boolean> cliAvailable;
    /** Consent survives whole rounds: the screen persists it to disk and re-injects it here. */
    private boolean consented;
    private State state = State.IDLE;
    private String pendingRequest;
    private String lastPlanJson;
    private String offerHash;
    private boolean needsTerrainConfirm;
    private boolean needsDestructiveConfirm;
    private String jobId;
    private int repairs;
    private long lastShownPercent = -1;
    /** The pause reason last announced (null while unpaused) - the same one is not repeated. */
    private String lastAnnouncedPause;
    /** The claim a finished BUILD job built on - what {@link SendRollback} names. */
    private String doneClaimId;
    /**
     * The claim the running BUILD job belongs to (27b): read from any progress doc while BUILDING -
     * what the "use my things?" answer and a parked materials directive get sent to.
     */
    private String buildingClaimId;
    /**
     * A materials directive the AI reply carried, parked until the approved build's first progress
     * doc reveals the claim id (no claim exists before approval, so nothing can be sent sooner).
     */
    private MaterialsDirective.Directive pendingDirective;
    /**
     * Whether a {@link SendMaterials} went out and its answer may still come back (Task 27e): only
     * then is a REJECTED offer in BUILDING the refusal of this flow's own ask, told to the child.
     */
    private boolean materialsSent;
    /**
     * Ordinary AI failures in a row (A1): neither cliMissing nor loginMissing count - those are
     * different problems that never heal by retrying. Deliberately NOT reset by
     * {@link #resetRound()}: a fresh request keeps the count, only a real success clears it.
     */
    private int consecutiveAiFailures;

    public BuildChatFlow(boolean consentGiven, PromptParts parts, Predicate<String> knownItem) {
        this(consentGiven, parts, knownItem, () -> PromptLanguage.JAPANESE_CODE);
    }

    /**
     * L1: {@code languageCode} supplies the game's Minecraft language code; it is consulted each
     * time an AI prompt is built, never cached.
     */
    public BuildChatFlow(boolean consentGiven, PromptParts parts, Predicate<String> knownItem,
                         Supplier<String> languageCode) {
        this(consentGiven, parts, knownItem, languageCode, () -> null);
    }

    /**
     * A1: {@code cliAvailable} reports the panel's CLI probe result; FALSE lets {@link #request}
     * refuse up front instead of asking consent for a call that cannot run.
     */
    public BuildChatFlow(boolean consentGiven, PromptParts parts, Predicate<String> knownItem,
                         Supplier<String> languageCode, Supplier<Boolean> cliAvailable) {
        this.consented = consentGiven;
        this.parts = Objects.requireNonNull(parts, "parts");
        this.knownItem = Objects.requireNonNull(knownItem, "knownItem");
        this.languageCode = Objects.requireNonNull(languageCode, "languageCode");
        this.cliAvailable = Objects.requireNonNull(cliAvailable, "cliAvailable");
    }

    public State state() {
        return state;
    }

    /**
     * The child sent {@code childText}. A fresh request is only taken while the flow is at rest
     * (IDLE/DONE/FAILED); in every other state the answer is just {@link #MSG_BUSY}. Without stored
     * consent the request is parked behind a NEED_CONSENT question first.
     */
    public List<Action> request(String childText) {
        Objects.requireNonNull(childText, "childText");
        if (state != State.IDLE && state != State.DONE && state != State.FAILED) {
            return List.of(say(MSG_BUSY));
        }
        resetRound();
        if (Boolean.FALSE.equals(cliAvailable.get())) {
            // A1: the probe proved the CLI absent, so refuse before the consent question and before
            // the request text is parked - neither can lead anywhere while no CLI can run.
            state = State.FAILED;
            return List.of(say(MSG_CLI_MISSING), new ShowButtons(List.of()));
        }
        if (!consented) {
            pendingRequest = childText;
            state = State.NEED_CONSENT;
            return List.of(say(MSG_CONSENT),
                    new ShowButtons(List.of(ButtonKind.CONSENT_YES, ButtonKind.CONSENT_NO)));
        }
        return ask(childText);
    }

    /** The consent buttons' answer; only meaningful in NEED_CONSENT. */
    public List<Action> consent(boolean yes) {
        if (state != State.NEED_CONSENT) {
            return List.of();
        }
        if (!yes) {
            pendingRequest = null;
            state = State.IDLE;
            return List.of(say(MSG_CANCELLED), new ShowButtons(List.of()));
        }
        consented = true;
        String held = pendingRequest;
        pendingRequest = null;
        return ask(held);
    }

    /** The AI's reply to the current AskAi; late replies in other states are dropped. */
    public List<Action> aiReply(StageResult result) {
        if (state != State.ASKING_AI) {
            return List.of();
        }
        // A1: every FAILED transition ends with an empty ShowButtons - no dangling buttons under
        // a failure the child cannot act on.
        if (result.cliMissing()) {
            state = State.FAILED;
            return List.of(say(MSG_CLI_MISSING), new ShowButtons(List.of()));
        }
        if (result.loginMissing()) {
            state = State.FAILED;
            return List.of(say(MSG_LOGIN_MISSING), new ShowButtons(List.of()));
        }
        if (!result.success()) {
            consecutiveAiFailures++;
            state = State.FAILED;
            return List.of(say(consecutiveAiFailures >= AI_FAILED_ADULT_AFTER
                            ? MSG_AI_FAILED_ADULT : MSG_AI_FAILED),
                    new ShowButtons(List.of()));
        }
        consecutiveAiFailures = 0;
        PlanReplyExtractor.Extracted extracted =
                PlanReplyExtractor.extract(result.text() == null ? "" : result.text());
        if (extracted.error() != null) {
            // A reply without a well-shaped JSON block means the reply rules were not kept; the
            // plan is not auto-repaired - the child re-asks in different words.
            state = State.FAILED;
            return List.of(say(MSG_PLAN_FAILED), new ShowButtons(List.of()));
        }
        lastPlanJson = extracted.json();
        List<Action> out = new ArrayList<>();
        if (extracted.childSays() != null && !extracted.childSays().isEmpty()) {
            out.add(say(MSG_SAYS, extracted.childSays()));
        }
        out.add(new SendPlan(extracted.json()));
        // 27b: the reply may carry a materials frame beside the plan. An accepted directive is
        // ALWAYS confirmed to the child - a silent rule change would be invisible; a rejected one
        // changes nothing and says so. With no claim yet it parks until the build's first doc.
        MaterialsDirective.Extracted materials =
                MaterialsDirective.extract(result.text(), knownItem);
        if (materials.directive() != null) {
            MaterialsDirective.Directive directive = materials.directive();
            if (!directive.isEmpty()) {
                pendingDirective = directive;
                out.add(say(MSG_MATERIALS_CHANGED));
                for (String id : directive.exclude()) {
                    out.add(say(MSG_MATERIALS_EXCLUDED, id));
                }
                for (String id : directive.include()) {
                    out.add(say(MSG_MATERIALS_INCLUDED, id));
                }
            }
        } else if (materials.found()) {
            out.add(say(MSG_MATERIALS_UNCLEAR));
        }
        state = State.WAITING_OFFER;
        return out;
    }

    /**
     * The server's newest offer document (OFFERED shows the summary and the BUILD/CANCEL buttons;
     * REJECTED/FAILED re-asks the AI with the technical messages up to {@link #MAX_REPAIRS} times).
     */
    public List<Action> offer(String offerJson) {
        if (state == State.UNDOING) {
            return undoRefused(offerJson);
        }
        if (state != State.WAITING_OFFER && state != State.BUILDING) {
            return List.of();
        }
        Map<String, Object> tree;
        String offerState;
        try {
            tree = mapAt(MiniJson.parse(offerJson), "offer");
            offerState = stringAt(tree.get("state"), "state");
        } catch (RuntimeException malformed) {
            // A doc that does not even carry its shape (no "state") cannot drive the flow. While the
            // offer is awaited that fails the round rather than letting an IllegalArgumentException
            // escape into a widget handler; while BUILDING it proves nothing about the approval
            // outcome, so it is dropped like every other unusable doc.
            if (state == State.BUILDING) {
                return List.of();
            }
            state = State.FAILED;
            return List.of(say(MSG_PLAN_FAILED), new ShowButtons(List.of()));
        }
        if (state == State.BUILDING) {
            // Between SendApprove and the first progress doc the only offer still open is the
            // approval itself, so REJECTED/FAILED here means the server turned the approve down:
            // the round ends politely instead of leaving the panel on "building" forever (M3b).
            // Once a jobId exists the doc is stale, and every other state is ignored as before.
            if (jobId == null
                    && (OFFER_REJECTED.equals(offerState) || OFFER_FAILED.equals(offerState))) {
                state = State.FAILED;
                return List.of(say(MSG_APPROVE_REFUSED), new ShowButtons(List.of()));
            }
            // 27e: while the job runs, the only offer doc the server still answers this panel with
            // is a bare REJECTED to a materials packet it could not take (a failed save, a refused
            // owner). It is told only when this flow really sent one; anything else stays ignored.
            if (materialsSent && OFFER_REJECTED.equals(offerState)) {
                materialsSent = false;
                return List.of(say(MSG_MATERIALS_REFUSED));
            }
            return List.of();
        }
        if (OFFER_OFFERED.equals(offerState)) {
            try {
                offerHash = stringAt(tree.get("hash"), "hash");
                needsTerrainConfirm = boolAt(tree.get("needsTerrainConfirm"), "needsTerrainConfirm");
                needsDestructiveConfirm =
                        boolAt(tree.get("needsDestructiveConfirm"), "needsDestructiveConfirm");
            } catch (RuntimeException malformed) {
                // The approve payload's hash field cannot encode null, so an OFFERED doc without a
                // hash is failed here instead of dying at the packet codec on つくる.
                state = State.FAILED;
                return List.of(say(MSG_PLAN_FAILED), new ShowButtons(List.of()));
            }
            List<Action> out = new ArrayList<>();
            out.add(say(MSG_OFFER, longAt(tree.get("blocks"), "blocks"),
                    longAt(tree.get("etaSeconds"), "etaSeconds")));
            long cut = longAt(tree.get("terrainCut"), "terrainCut");
            long fill = longAt(tree.get("terrainFill"), "terrainFill");
            if (cut > 0 || fill > 0) {
                out.add(say(MSG_OFFER_TERRAIN, cut, fill));
            }
            out.add(say(MSG_PLACE_IN_FRONT));
            // 27b: a survival offer says where the materials come from; creative spends nothing,
            // so naming chests there would be a lie - and a doc without the field names no source.
            if (POLICY_SURVIVAL.equals(tree.get("materialPolicy"))) {
                out.add(say(MSG_OFFER_SOURCE));
            }
            out.add(new ShowButtons(List.of(ButtonKind.BUILD, ButtonKind.CANCEL)));
            state = State.OFFERED;
            return out;
        }
        if (OFFER_REJECTED.equals(offerState) || OFFER_FAILED.equals(offerState)) {
            List<String> messages;
            try {
                messages = issueMessages(tree.get("issues"));
            } catch (RuntimeException malformed) {
                state = State.FAILED;
                return List.of(say(MSG_PLAN_FAILED), new ShowButtons(List.of()));
            }
            if (repairs < MAX_REPAIRS && lastPlanJson != null && !messages.isEmpty()) {
                repairs++;
                state = State.ASKING_AI;
                return List.of(new AskAi(RepairPromptBuilder.build(lastPlanJson, messages,
                        PromptLanguage.of(languageCode.get()))));
            }
            state = State.FAILED;
            return List.of(say(MSG_PLAN_FAILED), new ShowButtons(List.of()));
        }
        return List.of(); // WORKING or an unknown state: still waiting
    }

    /**
     * While UNDOING the only offer document still possible is the rollback's own refusal: the
     * server's handler answers a rollback it could not run with a bare REJECTED doc (M5). The
     * child hears the refusal line and gets the undo button back; anything else is ignored.
     */
    private List<Action> undoRefused(String offerJson) {
        String offerState;
        try {
            offerState = stringAt(mapAt(MiniJson.parse(offerJson), "offer").get("state"), "state");
        } catch (RuntimeException malformed) {
            return List.of(); // not a document the flow can read - the rollback's progress tells the rest
        }
        if (!OFFER_REJECTED.equals(offerState)) {
            return List.of();
        }
        state = State.DONE;
        return List.of(say(MSG_UNDO_REFUSED), new ShowButtons(List.of(ButtonKind.UNDO)));
    }

    /**
     * The つくる button, allowed only at OFFERED: pressing it counts as confirming the terraform /
     * destructive counts that were just spelled out by {@link #MSG_OFFER_TERRAIN} and friends -
     * that simplification is MVP-only (the brief; per-risk checkboxes come with the fleshing out).
     */
    public List<Action> approve() {
        if (state != State.OFFERED) {
            return List.of();
        }
        state = State.BUILDING;
        lastShownPercent = 0; // the announce below already speaks the 0% line
        List<Action> out = new ArrayList<>();
        out.add(new SendApprove(offerHash, needsTerrainConfirm, needsDestructiveConfirm));
        out.add(say(MSG_BUILDING, 0L));
        out.add(new ShowButtons(List.of()));
        return out;
    }

    /** Cancel: at OFFERED it just closes the offer; while BUILDING it also tells the server. */
    public List<Action> cancel() {
        if (state == State.OFFERED) {
            state = State.IDLE;
            return List.of(say(MSG_CANCELLED), new ShowButtons(List.of()));
        }
        if (state == State.BUILDING) {
            state = State.IDLE;
            List<Action> out = new ArrayList<>();
            if (jobId != null) {
                out.add(new SendCancel(jobId));
            }
            out.add(say(MSG_CANCELLED));
            return out;
        }
        return List.of();
    }

    /**
     * The server's newest progress document, read while BUILDING: {@code done}/{@code partial}
     * end the round, a new {@code pause} reason is announced once (the same reason is not repeated,
     * and a pause that cleared and returned is announced again), and a changed percent is spoken
     * (the same percent is never repeated).
     */
    public List<Action> progress(String progressJson) {
        if (state == State.UNDOING) {
            return undoProgress(progressJson);
        }
        if (state != State.BUILDING) {
            return List.of();
        }
        Map<String, Object> tree;
        try {
            tree = mapAt(MiniJson.parse(progressJson), "progress");
        } catch (RuntimeException malformed) {
            return List.of(); // a broken progress doc is not worth failing the round over
        }
        String id = tree.get("jobId") instanceof String s ? s : null;
        if (id != null) {
            jobId = id;
        }
        // kind says which job the document is (M5): only a finished BUILD earns the undo button -
        // a rollback's own done/partial must not offer to roll the rollback back.
        String kind = tree.get("kind") instanceof String s ? s : null;
        List<Action> out = new ArrayList<>();
        // 27b: the doc's claim is the claim every materials packet names. It also frees a directive
        // the AI reply parked: approval created the claim, so the first doc carrying it is the one
        // moment the rule can finally be sent - exactly once, then the park is empty.
        String claim = tree.get("claimId") instanceof String s ? s : null;
        if (claim != null) {
            buildingClaimId = claim;
        }
        if (pendingDirective != null && buildingClaimId != null) {
            out.add(new SendMaterials(buildingClaimId, pendingDirective.inventory(),
                    pendingDirective.exclude(), pendingDirective.include()));
            pendingDirective = null;
            materialsSent = true; // a REJECTED offer can now be this packet's refusal
        }
        if (Boolean.TRUE.equals(tree.get("done"))) {
            state = State.DONE;
            if (KIND_BUILD.equals(kind)) {
                doneClaimId = claim;
                out.add(say(MSG_DONE));
                out.add(new ShowButtons(List.of(ButtonKind.UNDO)));
                return out;
            }
            out.add(say(MSG_DONE));
            return out;
        }
        if (Boolean.TRUE.equals(tree.get("partial"))) {
            state = State.DONE;
            long missing = longOrZero(tree.get("unrepaired"))
                    + longOrZero(tree.get("conflicts"));
            out.add(say(MSG_PARTIAL, missing));
            if (KIND_BUILD.equals(kind)) {
                doneClaimId = claim;
                out.add(new ShowButtons(List.of(ButtonKind.UNDO)));
            }
            return out;
        }
        String pause = tree.get("pause") instanceof String s ? s : null;
        if (pause == null) {
            lastAnnouncedPause = null; // unpaused: the same reason may be announced again later
        } else {
            if (!pause.equals(lastAnnouncedPause)) {
                String pauseKey = PAUSE_KEY_PREFIX + pause.toLowerCase(Locale.ROOT);
                // 27b: a materials shortage while the owner has NOT allowed the inventory gets the
                // yes/no question INSTEAD of the plain line (the question already opens with "the
                // materials are short"; both lines in a row read as a stutter) - once per pause
                // interval (the dedupe by lastAnnouncedPause already keeps the same pause from asking
                // twice). With the switch already on, or with no claim to name, only the plain line stays.
                boolean inventoryAllowed = Boolean.TRUE.equals(tree.get("inventoryAllowed"));
                if (PAUSE_MATERIALS_MISSING.equals(pause) && !inventoryAllowed
                        && buildingClaimId != null) {
                    out.add(say(MSG_ASK_INVENTORY));
                    out.add(new ShowButtons(List.of(ButtonKind.INVENTORY_YES, ButtonKind.INVENTORY_NO)));
                } else if (PANEL_PAUSE_KEYS.containsKey(pauseKey)) {
                    out.add(say(PANEL_PAUSE_KEYS.get(pauseKey)));
                } else if (PAUSE_KEYS.contains(pauseKey)) {
                    out.add(say(pauseKey));
                }
            }
            lastAnnouncedPause = pause;
        }
        long percent = longOrZero(tree.get("percent"));
        if (percent == lastShownPercent) {
            return out;
        }
        lastShownPercent = percent;
        out.add(say(MSG_BUILDING, percent));
        return out;
    }

    /**
     * The "もちものからも つかう?" question's yes (27b): only while BUILDING and only when a
     * claim id was seen (no question was shown otherwise). The answer goes to the claim's supply
     * settings as {@link SendMaterials} - the id rides inside the packet, never a child's line.
     */
    public List<Action> inventoryYes() {
        if (state != State.BUILDING || buildingClaimId == null) {
            return List.of();
        }
        materialsSent = true; // a REJECTED offer can now be this packet's refusal
        return List.of(
                new SendMaterials(buildingClaimId, Boolean.TRUE, List.of(), List.of()),
                say(MSG_INVENTORY_ON), new ShowButtons(List.of()));
    }

    /**
     * The question's no: nothing is sent - the job stays paused until the chests hold the
     * materials (the retry is the server's own); only the buttons close.
     */
    public List<Action> inventoryNo() {
        if (state != State.BUILDING || buildingClaimId == null) {
            return List.of();
        }
        return List.of(say(MSG_INVENTORY_OFF), new ShowButtons(List.of()));
    }

    /**
     * The もとにもどす button, allowed only at DONE (M5): asks the child to confirm before
     * anything is sent - the built thing disappears, so one stray tap must not start it.
     */
    public List<Action> undo() {
        if (state != State.DONE) {
            return List.of();
        }
        state = State.CONFIRM_UNDO;
        return List.of(say(MSG_UNDO_ASK),
                new ShowButtons(List.of(ButtonKind.UNDO_YES, ButtonKind.UNDO_NO)));
    }

    /**
     * UNDO_YES at CONFIRM_UNDO: the claim id remembered from the build's last document goes to the
     * server inside {@link SendRollback} - it never reaches a child-facing line.
     */
    public List<Action> undoConfirmed() {
        if (state != State.CONFIRM_UNDO) {
            return List.of();
        }
        state = State.UNDOING;
        lastShownPercent = 0; // the announce below already speaks the 0% line
        List<Action> out = new ArrayList<>();
        out.add(new SendRollback(doneClaimId));
        out.add(say(MSG_UNDOING, 0L));
        out.add(new ShowButtons(List.of()));
        return out;
    }

    /** UNDO_NO at CONFIRM_UNDO: back to DONE with the undo button shown again. */
    public List<Action> undoCancelled() {
        if (state != State.CONFIRM_UNDO) {
            return List.of();
        }
        state = State.DONE;
        return List.of(new ShowButtons(List.of(ButtonKind.UNDO)));
    }

    /**
     * A progress document while UNDOING (M5): only the rollback job's own documents count -
     * {@code kind} anything but ROLLBACK is ignored. A mid-flight doc speaks a changed percent once,
     * done and partial end the round at IDLE so a fresh request can start.
     */
    private List<Action> undoProgress(String progressJson) {
        Map<String, Object> tree;
        try {
            tree = mapAt(MiniJson.parse(progressJson), "progress");
        } catch (RuntimeException malformed) {
            return List.of(); // a broken doc is not worth failing the undo over
        }
        // an undo ends with the claim's ORIGINAL build job turning ROLLED_BACK (measured in the real game); that document is not
        // a rollback job's own, so it is recognised by its state and the claim it belongs to
        String docState = tree.get("state") instanceof String s ? s : null;
        String docClaim = tree.get("claimId") instanceof String c ? c : null;
        if (STATE_ROLLED_BACK.equals(docState) && docClaim != null && docClaim.equals(doneClaimId)) {
            state = State.IDLE;
            return List.of(say(MSG_UNDO_DONE));
        }
        String kind = tree.get("kind") instanceof String s ? s : null;
        if (!KIND_ROLLBACK.equals(kind)) {
            return List.of();
        }
        if (Boolean.TRUE.equals(tree.get("done"))) {
            state = State.IDLE;
            return List.of(say(MSG_UNDO_DONE));
        }
        if (Boolean.TRUE.equals(tree.get("partial"))) {
            state = State.IDLE;
            long missing = longOrZero(tree.get("unrepaired"))
                    + longOrZero(tree.get("conflicts"));
            return List.of(say(MSG_UNDO_PARTIAL, missing));
        }
        long percent = longOrZero(tree.get("percent"));
        if (percent == lastShownPercent) {
            return List.of();
        }
        lastShownPercent = percent;
        return List.of(say(MSG_UNDOING, percent));
    }

    private List<Action> ask(String childText) {
        state = State.ASKING_AI;
        return List.of(new AskAi(
                BuildPromptBuilder.build(childText, parts.sampleJson(), parts.partsCatalog(),
                        parts.allowedBlocks(), PromptLanguage.of(languageCode.get()))));
    }

    /** Per-round fields; {@code consented} is deliberately not part of a round. */
    private void resetRound() {
        pendingRequest = null;
        lastPlanJson = null;
        offerHash = null;
        needsTerrainConfirm = false;
        needsDestructiveConfirm = false;
        jobId = null;
        repairs = 0;
        lastShownPercent = -1;
        lastAnnouncedPause = null;
        doneClaimId = null;
        buildingClaimId = null;
        pendingDirective = null;
        materialsSent = false;
    }

    /** The technical {@code message} texts of the offer's issues - for the repair prompt only. */
    private static List<String> issueMessages(Object issuesField) {
        List<String> out = new ArrayList<>();
        if (issuesField == null) {
            return out;
        }
        for (Object entry : listAt(issuesField, "issues")) {
            Object message = mapAt(entry, "issues[]").get("message");
            if (message instanceof String s && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static Say say(String key, Object... args) {
        List<String> text = new ArrayList<>(args.length);
        for (Object arg : args) {
            text.add(String.valueOf(arg));
        }
        return new Say(key, text);
    }

    // Minimal shape readers for the offer/progress documents (JsonReads lives in construction.core,
    // which this package may not import). A wrong shape throws, never silently coerces.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(what + " is not an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listAt(Object value, String what) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(what + " is not an array");
        }
        return (List<Object>) value;
    }

    private static String stringAt(Object value, String what) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(what + " is not a string");
        }
        return s;
    }

    private static long longAt(Object value, String what) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException(what + " is not a number");
        }
        return n.longValue();
    }

    /** Progress fields that may legitimately be absent (a mid-flight doc) read as 0. */
    private static long longOrZero(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static boolean boolAt(Object value, String what) {
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException(what + " is not a boolean");
        }
        return b;
    }
}
