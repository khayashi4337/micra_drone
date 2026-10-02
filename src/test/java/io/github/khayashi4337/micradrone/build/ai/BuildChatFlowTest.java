package io.github.khayashi4337.micradrone.build.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.ai.BuildChatFlow.Action;
import io.github.khayashi4337.micradrone.build.ai.BuildChatFlow.ButtonKind;
import io.github.khayashi4337.micradrone.build.ai.BuildChatFlow.State;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import io.github.khayashi4337.micradrone.construction.core.ChildMessages;
import io.github.khayashi4337.micradrone.construction.core.PauseReason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildChatFlowTest {
    private static final BuildChatFlow.PromptParts PARTS =
            new BuildChatFlow.PromptParts("{\"ops\":[]}", "micra:structure(...)", "minecraft:stone");
    /** The item ids the test "catalog" knows; a materials directive naming anything else is invalid. */
    private static final java.util.Set<String> KNOWN_ITEMS = java.util.Set.of(
            "minecraft:diamond", "minecraft:emerald", "minecraft:cobblestone",
            "minecraft:stone", "minecraft:oak_planks");

    private static BuildChatFlow flow(boolean consentGiven) {
        return new BuildChatFlow(consentGiven, PARTS, KNOWN_ITEMS::contains);
    }

    private static <T extends Action> T only(List<Action> actions, Class<T> type) {
        assertEquals(1, actions.size(), "expected exactly one action in " + actions);
        return assertInstanceOf(type, actions.get(0));
    }

    private static <T extends Action> T actionAt(List<Action> actions, int index, Class<T> type) {
        return assertInstanceOf(type, actions.get(index));
    }

    private static List<String> sayKeys(List<Action> actions) {
        return actions.stream()
                .filter(a -> a instanceof BuildChatFlow.Say)
                .map(a -> ((BuildChatFlow.Say) a).key())
                .toList();
    }

    private static List<String> allSayText(List<Action> actions) {
        return actions.stream()
                .filter(a -> a instanceof BuildChatFlow.Say)
                .flatMap(a -> {
                    BuildChatFlow.Say s = (BuildChatFlow.Say) a;
                    return java.util.stream.Stream.concat(java.util.stream.Stream.of(s.key()), s.args().stream());
                })
                .toList();
    }

    private static BuildChatFlow.AskAi askAi(List<Action> actions) {
        return actions.stream()
                .filter(a -> a instanceof BuildChatFlow.AskAi)
                .map(a -> (BuildChatFlow.AskAi) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no AskAi in " + actions));
    }

    private static String offerJson(String state, String hash, long blocks, long etaSeconds,
            long cut, long fill, boolean needsTerrain, boolean needsDestructive, List<Object> issues) {
        return offerJson(state, hash, blocks, etaSeconds, cut, fill, needsTerrain, needsDestructive,
                issues, null);
    }

    /** The offer doc as OfferView writes it since Task 27b: {@code materialPolicy} included (null = none). */
    private static String offerJson(String state, String hash, long blocks, long etaSeconds,
            long cut, long fill, boolean needsTerrain, boolean needsDestructive, List<Object> issues,
            String materialPolicy) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("state", state);
        t.put("hash", hash);
        t.put("blocks", blocks);
        t.put("etaSeconds", etaSeconds);
        t.put("terrainCut", cut);
        t.put("terrainFill", fill);
        t.put("fluids", 0L);
        t.put("leaves", 0L);
        t.put("emptyContainers", 0L);
        t.put("needsTerrainConfirm", needsTerrain);
        t.put("needsDestructiveConfirm", needsDestructive);
        t.put("materialPolicy", materialPolicy);
        t.put("issues", issues);
        return MiniJson.write(t);
    }

    private static Map<String, Object> issueEntry(String childKey, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("childKey", childKey);
        e.put("message", message);
        return e;
    }

    private static String progressJson(String jobId, long percent, boolean done, boolean partial,
            long unrepaired, long conflicts) {
        return progressJson(jobId, null, null, percent, done, partial, unrepaired, conflicts);
    }

    /** A progress doc as ProgressView writes it since M5: {@code kind} and {@code claimId} included. */
    private static String progressJson(String jobId, String kind, String claimId, long percent,
            boolean done, boolean partial, long unrepaired, long conflicts) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("jobId", jobId);
        t.put("kind", kind);
        t.put("claimId", claimId);
        t.put("state", done ? "VERIFIED" : partial ? "PARTIAL" : "RUNNING");
        t.put("cursor", percent);
        t.put("total", 100L);
        t.put("percent", percent);
        t.put("pause", null);
        t.put("unrepaired", unrepaired);
        t.put("conflicts", conflicts);
        t.put("done", done);
        t.put("partial", partial);
        return MiniJson.write(t);
    }

    /** A mid-flight progress doc with an explicit {@code pause} name (null = not paused). */
    private static String pausedProgressJson(String jobId, long percent, String pause) {
        return pausedProgressJson(jobId, null, percent, pause, false);
    }

    /**
     * A mid-flight progress doc as Task 27b needs it: {@code kind}, {@code claimId} and
     * {@code inventoryAllowed} written, so the materials-missing question knows the claim it
     * would permit and whether the owner already said yes.
     */
    private static String pausedProgressJson(String jobId, String claimId, long percent, String pause,
            boolean inventoryAllowed) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("jobId", jobId);
        t.put("kind", "BUILD");
        t.put("claimId", claimId);
        t.put("state", pause == null ? "RUNNING" : "PAUSED");
        t.put("cursor", percent);
        t.put("total", 100L);
        t.put("percent", percent);
        t.put("pause", pause);
        t.put("inventoryAllowed", inventoryAllowed);
        t.put("unrepaired", 0L);
        t.put("conflicts", 0L);
        t.put("done", false);
        t.put("partial", false);
        return MiniJson.write(t);
    }

    private static StageResult aiOk(String reply) {
        return new StageResult(true, reply, null, false);
    }

    /** Drives a consented flow up to WAITING_OFFER with {@code {"ops":[]}} as the plan. */
    private static BuildChatFlow atWaitingOffer() {
        BuildChatFlow flow = flow(true);
        flow.request("こやを たてて");
        flow.aiReply(aiOk("こやを つくるよ\n```json\n{\"ops\":[]}\n```"));
        assertEquals(State.WAITING_OFFER, flow.state());
        return flow;
    }

    /** Drives a flow up to OFFERED and returns the actions the offer produced. */
    private static BuildChatFlow atOffered() {
        BuildChatFlow flow = atWaitingOffer();
        flow.offer(offerJson("OFFERED", "hash-1", 5, 20, 0, 0, false, false, List.of()));
        assertEquals(State.OFFERED, flow.state());
        return flow;
    }

    /** Drives a flow to BUILDING right after つくる: SendApprove is out, no jobId seen yet. */
    private static BuildChatFlow atBuilding() {
        BuildChatFlow flow = atOffered();
        flow.approve();
        assertEquals(State.BUILDING, flow.state());
        return flow;
    }

    // ---- (a) consent ----------------------------------------------------------------------------------

    @Test
    void aFirstRequestWithoutConsentAsksAndWaits() {
        BuildChatFlow flow = flow(false);
        List<Action> actions = flow.request("こやを たてて");
        assertEquals(State.NEED_CONSENT, flow.state());
        assertEquals(2, actions.size());
        assertEquals(BuildChatFlow.MSG_CONSENT, actionAt(actions, 0, BuildChatFlow.Say.class).key());
        assertEquals(List.of(ButtonKind.CONSENT_YES, ButtonKind.CONSENT_NO),
                actionAt(actions, 1, BuildChatFlow.ShowButtons.class).buttons());
    }

    @Test
    void consentYesAsksTheAiWithTheHeldRequestText() {
        BuildChatFlow flow = flow(false);
        flow.request("あかい やねの こや");
        List<Action> actions = flow.consent(true);
        assertEquals(State.ASKING_AI, flow.state());
        assertTrue(askAi(actions).prompt().contains("あかい やねの こや"),
                "the held request text must reach the AI prompt");
    }

    @Test
    void consentNoReturnsToIdleAndSaysCancelled() {
        BuildChatFlow flow = flow(false);
        flow.request("こやを たてて");
        List<Action> actions = flow.consent(false);
        assertEquals(State.IDLE, flow.state());
        assertTrue(sayKeys(actions).contains(BuildChatFlow.MSG_CANCELLED));
    }

    @Test
    void aConsentedFlowAsksImmediately() {
        BuildChatFlow flow = flow(true);
        List<Action> actions = flow.request("こやを たてて");
        assertEquals(State.ASKING_AI, flow.state());
        BuildChatFlow.AskAi ask = only(actions, BuildChatFlow.AskAi.class);
        assertTrue(ask.prompt().contains("こやを たてて"));
    }

    @Test
    void consentOutsideNeedConsentDoesNothing() {
        BuildChatFlow flow = flow(true);
        assertEquals(List.of(), flow.consent(true));
        assertEquals(State.IDLE, flow.state());
    }

    // ---- (b) the AI's reply ----------------------------------------------------------------------------

    @Test
    void aMissingCliFailsWithTheCliMissingLine() {
        BuildChatFlow flow = flow(true);
        flow.request("こやを たてて");
        List<Action> actions = flow.aiReply(new StageResult(false, null, "not found", true));
        assertEquals(State.FAILED, flow.state());
        assertEquals(BuildChatFlow.MSG_CLI_MISSING, only(actions, BuildChatFlow.Say.class).key());
    }

    @Test
    void aFailedReplySaysAiFailed() {
        BuildChatFlow flow = flow(true);
        flow.request("こやを たてて");
        List<Action> actions = flow.aiReply(new StageResult(false, null, "timeout", false));
        assertEquals(State.FAILED, flow.state());
        assertEquals(BuildChatFlow.MSG_AI_FAILED, only(actions, BuildChatFlow.Say.class).key());
    }

    @Test
    void aReplyWithoutJsonSaysPlanFailed() {
        BuildChatFlow flow = flow(true);
        flow.request("こやを たてて");
        List<Action> actions = flow.aiReply(aiOk("ごめん、わからない"));
        assertEquals(State.FAILED, flow.state());
        assertEquals(BuildChatFlow.MSG_PLAN_FAILED, only(actions, BuildChatFlow.Say.class).key());
    }

    @Test
    void aGoodReplySaysTheOneLinerAndSendsThePlan() {
        BuildChatFlow flow = flow(true);
        flow.request("こやを たてて");
        List<Action> actions = flow.aiReply(aiOk("あかい こやを つくるよ\n```json\n{\"ops\":[]}\n```"));
        assertEquals(State.WAITING_OFFER, flow.state());
        assertEquals(2, actions.size());
        BuildChatFlow.Say say = actionAt(actions, 0, BuildChatFlow.Say.class);
        assertEquals(BuildChatFlow.MSG_SAYS, say.key());
        assertEquals(List.of("あかい こやを つくるよ"), say.args());
        assertEquals("{\"ops\":[]}", actionAt(actions, 1, BuildChatFlow.SendPlan.class).json());
    }

    @Test
    void anEmptyOneLinerIsSkipped() {
        BuildChatFlow flow = flow(true);
        flow.request("こやを たてて");
        List<Action> actions = flow.aiReply(aiOk("```json\n{\"ops\":[]}\n```"));
        assertEquals(State.WAITING_OFFER, flow.state());
        assertEquals("{\"ops\":[]}", only(actions, BuildChatFlow.SendPlan.class).json());
    }

    @Test
    void aReplyArrivingOutsideAskingIsIgnored() {
        BuildChatFlow flow = flow(true);
        assertEquals(List.of(), flow.aiReply(aiOk("```json\n{\"ops\":[]}\n```")));
        assertEquals(State.IDLE, flow.state());
    }

    // ---- (c) the server's offer ------------------------------------------------------------------------

    @Test
    void anOfferedOfferShowsTheCountsAndTheBuildButtons() {
        BuildChatFlow flow = atWaitingOffer();
        List<Action> actions = flow.offer(
                offerJson("OFFERED", "hash-9", 7, 40, 2, 4, true, true, List.of()));
        assertEquals(State.OFFERED, flow.state());
        List<BuildChatFlow.Say> says = actions.stream()
                .filter(a -> a instanceof BuildChatFlow.Say)
                .map(a -> (BuildChatFlow.Say) a)
                .toList();
        assertEquals(BuildChatFlow.MSG_OFFER, says.get(0).key());
        assertEquals(List.of("7", "40"), says.get(0).args());
        assertEquals(BuildChatFlow.MSG_OFFER_TERRAIN, says.get(1).key());
        assertEquals(List.of("2", "4"), says.get(1).args());
        assertEquals(BuildChatFlow.MSG_PLACE_IN_FRONT, says.get(2).key());
        BuildChatFlow.ShowButtons buttons = actions.stream()
                .filter(a -> a instanceof BuildChatFlow.ShowButtons)
                .map(a -> (BuildChatFlow.ShowButtons) a)
                .findFirst().orElseThrow();
        assertEquals(List.of(ButtonKind.BUILD, ButtonKind.CANCEL), buttons.buttons());
    }

    @Test
    void theTerrainLineAppearsWhenOnlyCuttingOrOnlyFillingIsNeeded() {
        // a hut on flat grass cuts 49 and fills 0 (measured in the real game); either count alone must be told
        assertEquals(List.of("49", "0"), terrainSay(offerJson("OFFERED", "h", 5, 20, 49, 0, true, false, List.of())).args());
        assertEquals(List.of("0", "3"), terrainSay(offerJson("OFFERED", "h", 5, 20, 0, 3, true, false, List.of())).args());
    }

    @Test
    void noTerrainLineWhenNothingIsCutOrFilled() {
        BuildChatFlow flow = atWaitingOffer();
        List<Action> actions = flow.offer(offerJson("OFFERED", "h", 5, 20, 0, 0, false, false, List.of()));
        assertFalse(sayKeys(actions).contains(BuildChatFlow.MSG_OFFER_TERRAIN), sayKeys(actions).toString());
    }

    private static BuildChatFlow.Say terrainSay(String offerJson) {
        List<Action> actions = atWaitingOffer().offer(offerJson);
        return actions.stream()
                .filter(a -> a instanceof BuildChatFlow.Say s && s.key().equals(BuildChatFlow.MSG_OFFER_TERRAIN))
                .map(a -> (BuildChatFlow.Say) a)
                .findFirst().orElseThrow(() -> new AssertionError("no terrain line in " + actions));
    }

    @Test
    void anOfferWithoutTerrainSkipsTheTerrainLine() {
        BuildChatFlow flow = atWaitingOffer();
        List<Action> actions = flow.offer(
                offerJson("OFFERED", "hash-1", 5, 20, 0, 0, false, false, List.of()));
        assertFalse(sayKeys(actions).contains(BuildChatFlow.MSG_OFFER_TERRAIN));
        assertEquals(State.OFFERED, flow.state());
    }

    @Test
    void aRejectedOfferAsksTheAiToRepairWithTheIssueMessages() {
        BuildChatFlow flow = atWaitingOffer();
        List<Action> actions = flow.offer(offerJson("REJECTED", null, 0, 0, 0, 0, false, false,
                List.of(issueEntry("micradrone.build.issue.e_unknown_part", "unknown part id: micra:zz"))));
        assertEquals(State.ASKING_AI, flow.state());
        BuildChatFlow.AskAi ask = askAi(actions);
        assertTrue(ask.prompt().contains("unknown part id: micra:zz"),
                "the repair prompt must carry the server's technical message");
        assertTrue(ask.prompt().contains("{\"ops\":[]}"),
                "the repair prompt must carry the rejected plan JSON");
    }

    @Test
    void twoRepairsThenTheThirdRejectionFails() {
        BuildChatFlow flow = atWaitingOffer();
        String rejected = offerJson("REJECTED", null, 0, 0, 0, 0, false, false,
                List.of(issueEntry(null, "bad part")));
        flow.offer(rejected);   // repair 1
        flow.aiReply(aiOk("```json\n{\"ops\":[],\"v\":2}\n```"));
        flow.offer(rejected);   // repair 2
        flow.aiReply(aiOk("```json\n{\"ops\":[],\"v\":3}\n```"));
        List<Action> actions = flow.offer(rejected);   // beyond MAX_REPAIRS = 2
        assertEquals(State.FAILED, flow.state());
        assertEquals(BuildChatFlow.MSG_PLAN_FAILED, only(actions, BuildChatFlow.Say.class).key());
    }

    @Test
    void aFailedOfferAlsoConsumesARepairRound() {
        BuildChatFlow flow = atWaitingOffer();
        List<Action> actions = flow.offer(offerJson("FAILED", null, 0, 0, 0, 0, false, false,
                List.of(issueEntry("micradrone.build.issue.e_site_blocked", "site has foreign blocks"))));
        assertEquals(State.ASKING_AI, flow.state());
        assertFalse(askAi(actions) == null);
    }

    @Test
    void technicalIssueMessagesNeverReachAChildFacingLine() {
        BuildChatFlow flow = atWaitingOffer();
        for (int round = 0; round < BuildChatFlow.MAX_REPAIRS + 1; round++) {
            List<Action> actions = flow.offer(offerJson("REJECTED", null, 0, 0, 0, 0, false, false,
                    List.of(issueEntry("micradrone.build.issue.e_site_blocked",
                            "E-SITE-BLOCKED: secret-tech-detail-" + round))));
            for (String text : allSayText(actions)) {
                assertFalse(text.contains("secret-tech-detail-" + round),
                        "the technical message leaked into a child line: " + text);
            }
            if (flow.state() == State.ASKING_AI) {
                flow.aiReply(aiOk("```json\n{\"ops\":[]}\n```"));
            }
        }
    }

    @Test
    void anOfferWhileNotWaitingIsIgnored() {
        BuildChatFlow flow = flow(true);
        assertEquals(List.of(), flow.offer(offerJson("OFFERED", "h", 1, 1, 0, 0, false, false, List.of())));
        assertEquals(State.IDLE, flow.state());
    }

    // ---- (d) approve / cancel --------------------------------------------------------------------------

    @Test
    void approveFromOfferedSendsTheHashAndTheConfirmFlags() {
        BuildChatFlow flow = atWaitingOffer();
        flow.offer(offerJson("OFFERED", "hash-abc", 5, 20, 2, 4, true, true, List.of()));
        List<Action> actions = flow.approve();
        assertEquals(State.BUILDING, flow.state());
        BuildChatFlow.SendApprove approve = actions.stream()
                .filter(a -> a instanceof BuildChatFlow.SendApprove)
                .map(a -> (BuildChatFlow.SendApprove) a)
                .findFirst().orElseThrow();
        assertEquals("hash-abc", approve.hash());
        assertTrue(approve.confirmTerraform());
        assertTrue(approve.confirmDestructive());
        assertTrue(sayKeys(actions).contains(BuildChatFlow.MSG_BUILDING));
        assertTrue(actions.stream()
                .filter(a -> a instanceof BuildChatFlow.ShowButtons)
                .map(a -> ((BuildChatFlow.ShowButtons) a).buttons())
                .findFirst().orElseThrow()
                .isEmpty(), "the buttons are hidden while building");
    }

    @Test
    void approveIsOnlyAllowedFromOffered() {
        BuildChatFlow flow = flow(true);
        assertEquals(List.of(), flow.approve());
        assertEquals(State.IDLE, flow.state());
        BuildChatFlow waiting = atWaitingOffer();
        assertEquals(List.of(), waiting.approve());
        assertEquals(State.WAITING_OFFER, waiting.state());
    }

    @Test
    void cancelFromOfferedReturnsToIdle() {
        BuildChatFlow flow = atOffered();
        List<Action> actions = flow.cancel();
        assertEquals(State.IDLE, flow.state());
        assertTrue(sayKeys(actions).contains(BuildChatFlow.MSG_CANCELLED));
    }

    @Test
    void cancelFromBuildingSendsTheJobCancel() {
        BuildChatFlow flow = atOffered();
        flow.approve();
        flow.progress(progressJson("job-7", 10, false, false, 0, 0));
        List<Action> actions = flow.cancel();
        assertEquals(State.IDLE, flow.state());
        BuildChatFlow.SendCancel cancel = actions.stream()
                .filter(a -> a instanceof BuildChatFlow.SendCancel)
                .map(a -> (BuildChatFlow.SendCancel) a)
                .findFirst().orElseThrow();
        assertEquals("job-7", cancel.jobId());
    }

    // ---- (e) progress -----------------------------------------------------------------------------------

    @Test
    void progressPercentIsShownOnlyWhenItChanges() {
        BuildChatFlow flow = atOffered();
        flow.approve();
        List<Action> at10 = flow.progress(progressJson("job-1", 10, false, false, 0, 0));
        BuildChatFlow.Say say = only(at10, BuildChatFlow.Say.class);
        assertEquals(BuildChatFlow.MSG_BUILDING, say.key());
        assertEquals(List.of("10"), say.args());
        assertEquals(List.of(), flow.progress(progressJson("job-1", 10, false, false, 0, 0)),
                "the same percent is not repeated");
        List<Action> at30 = flow.progress(progressJson("job-1", 30, false, false, 0, 0));
        assertEquals(List.of("30"), only(at30, BuildChatFlow.Say.class).args());
    }

    @Test
    void aDoneProgressFinishes() {
        BuildChatFlow flow = atOffered();
        flow.approve();
        List<Action> actions = flow.progress(progressJson("job-1", 100, true, false, 0, 0));
        assertEquals(State.DONE, flow.state());
        assertEquals(BuildChatFlow.MSG_DONE, only(actions, BuildChatFlow.Say.class).key());
    }

    @Test
    void aPartialProgressReportsTheMissingCount() {
        BuildChatFlow flow = atOffered();
        flow.approve();
        List<Action> actions = flow.progress(progressJson("job-1", 90, false, true, 2, 1));
        assertEquals(State.DONE, flow.state());
        BuildChatFlow.Say say = only(actions, BuildChatFlow.Say.class);
        assertEquals(BuildChatFlow.MSG_PARTIAL, say.key());
        assertEquals(List.of("3"), say.args(), "unrepaired 2 + conflicts 1 = 3 places left out");
    }

    @Test
    void progressWhileNotBuildingIsIgnored() {
        BuildChatFlow flow = flow(true);
        assertEquals(List.of(), flow.progress(progressJson("job-1", 50, false, false, 0, 0)));
        assertEquals(State.IDLE, flow.state());
    }

    // ---- (f) busy and restart ----------------------------------------------------------------------------

    @Test
    void aRequestWhileBusyJustSaysBusy() {
        BuildChatFlow flow = atOffered();
        flow.approve();
        List<Action> actions = flow.request("べつの ものを たてて");
        assertEquals(State.BUILDING, flow.state());
        assertEquals(BuildChatFlow.MSG_BUSY, only(actions, BuildChatFlow.Say.class).key());
    }

    @Test
    void aNewRequestAfterDoneStartsFresh() {
        BuildChatFlow flow = atOffered();
        flow.approve();
        flow.progress(progressJson("job-1", 100, true, false, 0, 0));
        assertEquals(State.DONE, flow.state());
        List<Action> actions = flow.request("つぎは おおきい や");
        assertEquals(State.ASKING_AI, flow.state(), "consent carries over within the session");
        assertTrue(askAi(actions).prompt().contains("つぎは おおきい や"));
    }

    @Test
    void aNewRequestAfterFailedStartsFresh() {
        BuildChatFlow flow = flow(true);
        flow.request("こや");
        flow.aiReply(new StageResult(false, null, "boom", false));
        assertEquals(State.FAILED, flow.state());
        List<Action> actions = flow.request("もういちど こや");
        assertEquals(State.ASKING_AI, flow.state());
        assertTrue(askAi(actions).prompt().contains("もういちど こや"));
    }

    // ---- (g) M3b: an approve the server turned down, and pauses while building ------------------------

    @Test
    void aRejectedOfferWhileWaitingForTheApproveFailsPolitely() {
        BuildChatFlow flow = atBuilding();
        List<Action> actions = flow.offer(offerJson("REJECTED", null, 0, 0, 0, 0, false, false,
                List.of(issueEntry("micradrone.build.reject.blocking_issues",
                        "approval refused by BLOCKING_ISSUES: E-SITE-BLOCKED:spot#not_terraformable"))));
        assertEquals(State.FAILED, flow.state());
        assertEquals(2, actions.size());
        assertEquals(BuildChatFlow.MSG_APPROVE_REFUSED,
                actionAt(actions, 0, BuildChatFlow.Say.class).key());
        assertTrue(actionAt(actions, 1, BuildChatFlow.ShowButtons.class).buttons().isEmpty(),
                "the refusal leaves the button row hidden - there is nothing left to approve");
        for (String text : allSayText(actions)) {
            assertFalse(text.contains("not_terraformable"), "the technical reason leaked: " + text);
            assertFalse(text.contains("E-SITE-BLOCKED"), "the issue code leaked: " + text);
        }
        List<Action> again = flow.request("べつの ばしょで たてて");
        assertEquals(State.ASKING_AI, flow.state(), "a fresh request is accepted after the refusal");
        assertTrue(askAi(again).prompt().contains("べつの ばしょで たてて"));
    }

    @Test
    void aFailedOfferWhileWaitingForTheApproveAlsoEndsTheRound() {
        BuildChatFlow flow = atBuilding();
        List<Action> actions = flow.offer(offerJson("FAILED", null, 0, 0, 0, 0, false, false,
                List.of(issueEntry(null, "internal detail"))));
        assertEquals(State.FAILED, flow.state());
        assertEquals(BuildChatFlow.MSG_APPROVE_REFUSED,
                actionAt(actions, 0, BuildChatFlow.Say.class).key());
    }

    @Test
    void anOfferAfterTheJobStartedIsIgnoredEvenWhenRejected() {
        BuildChatFlow flow = atBuilding();
        flow.progress(progressJson("job-9", 5, false, false, 0, 0)); // the job exists now
        assertEquals(List.of(), flow.offer(offerJson("REJECTED", null, 0, 0, 0, 0, false, false,
                List.of(issueEntry(null, "late refusal")))));
        assertEquals(State.BUILDING, flow.state());
    }

    @Test
    void anOfferedDocWhileWaitingForTheApproveIsIgnored() {
        BuildChatFlow flow = atBuilding();
        assertEquals(List.of(),
                flow.offer(offerJson("OFFERED", "h", 1, 1, 0, 0, false, false, List.of())));
        assertEquals(State.BUILDING, flow.state());
    }

    @Test
    void aMalformedOfferWhileWaitingForTheApproveIsDropped() {
        BuildChatFlow flow = atBuilding();
        assertEquals(List.of(), flow.offer("this is not json"));
        assertEquals(State.BUILDING, flow.state());
    }

    @Test
    void aPauseIsAnnouncedOnceAndAgainAfterItClears() {
        BuildChatFlow flow = atBuilding();
        List<Action> first = flow.progress(pausedProgressJson("job-1", 0, "CHUNK_UNLOADED"));
        assertEquals(List.of("micradrone.build.pause.chunk_unloaded"), sayKeys(first));
        assertEquals(List.of(), flow.progress(pausedProgressJson("job-1", 0, "CHUNK_UNLOADED")),
                "the same pause is not repeated");
        assertEquals(List.of(), flow.progress(pausedProgressJson("job-1", 0, null)),
                "the pause clearing speaks nothing by itself");
        List<Action> again = flow.progress(pausedProgressJson("job-1", 0, "CHUNK_UNLOADED"));
        assertEquals(List.of("micradrone.build.pause.chunk_unloaded"), sayKeys(again),
                "a pause that came back is announced again");
    }

    @Test
    void aDifferentPauseIsAnnouncedAgain() {
        BuildChatFlow flow = atBuilding();
        flow.progress(pausedProgressJson("job-1", 0, "CHUNK_UNLOADED"));
        List<Action> second = flow.progress(pausedProgressJson("job-1", 0, "MATERIALS_MISSING"));
        assertEquals(List.of("micradrone.build.pause.materials_missing"), sayKeys(second));
    }

    @Test
    void aPauseNameTheServerNeverWritesStaysSilent() {
        BuildChatFlow flow = atBuilding();
        assertEquals(List.of(), flow.progress(pausedProgressJson("job-1", 0, "NOT_A_REASON")),
                "only the registered pause keys may reach a child line");
    }

    @Test
    void anEntityInTheWayPauseIsSpokenToThePanel() {
        BuildChatFlow flow = atBuilding();
        List<Action> actions = flow.progress(pausedProgressJson("job-1", 0, "ENTITY_IN_WAY"));
        assertEquals(List.of("micradrone.build.pause.entity_in_way"), sayKeys(actions));
    }

    private static final java.util.Set<PauseReason> PANEL_WORDED = java.util.Set.of(
            PauseReason.RECOVERY_NEEDED, PauseReason.SITE_CHANGED);

    @Test
    void everyPauseReasonMapsToItsPauseKeyExceptTheTwoWithPanelWording() {
        BuildChatFlow flow = atBuilding();
        for (PauseReason r : PauseReason.values()) {
            if (PANEL_WORDED.contains(r)) {
                continue;
            }
            List<Action> actions = flow.progress(pausedProgressJson("job-1", 0, r.name()));
            assertEquals(List.of(ChildMessages.pause(r)), sayKeys(actions), r.name());
        }
    }

    @Test
    void theRecoveryAndSiteChangedPausesUseThePanelWordingWithoutAnyArgument() {
        for (var entry : java.util.Map.of(PauseReason.RECOVERY_NEEDED, BuildChatFlow.MSG_PAUSE_RECOVERY,
                PauseReason.SITE_CHANGED, BuildChatFlow.MSG_PAUSE_SITE_CHANGED).entrySet()) {
            BuildChatFlow flow = atBuilding();
            List<Action> actions = flow.progress(pausedProgressJson("job-42", 0, entry.getKey().name()));
            BuildChatFlow.Say say = only(actions, BuildChatFlow.Say.class);
            assertEquals(entry.getValue(), say.key());
            assertEquals(List.of(), say.args(), "the panel line never carries the job id");
        }
    }

    @Test
    void noPauseLineTheFlowCanSayShowsACommandOrAnIdToAChild() throws java.io.IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> ja = (Map<String, Object>) MiniJson.parse(java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/assets/micradrone/lang/ja_jp.json"),
                java.nio.charset.StandardCharsets.UTF_8));
        for (PauseReason r : PauseReason.values()) {
            BuildChatFlow flow = atBuilding();
            for (Action a : flow.progress(pausedProgressJson("job-42", 0, r.name()))) {
                if (a instanceof BuildChatFlow.Say say) {
                    String text = (String) ja.get(say.key());
                    assertTrue(text != null && !text.contains("/micradrone") && !text.contains("%1$s")
                            && !text.contains("recover") && !text.contains("resume"), r + ": " + text);
                }
            }
        }
    }

    // ---- (h) M5: the もとにもどす (undo) button ------------------------------------------------------

    private static final String CLAIM = "claim-job-1";

    /** Drives a flow to DONE through a BUILD job's done document carrying its claim id. */
    private static BuildChatFlow atDone(String claimId) {
        BuildChatFlow flow = atBuilding();
        flow.progress(progressJson("job-1", "BUILD", claimId, 100, true, false, 0, 0));
        assertEquals(State.DONE, flow.state());
        return flow;
    }

    /** Drives a flow to UNDOING: done build -> undo -> confirm. */
    /** The document the real game ends an undo with: the ORIGINAL build job of the claim, now ROLLED_BACK (not "done"). */
    private static String rolledBackJson(String claimId) {
        return "{\"jobId\":\"job-1\",\"kind\":\"BUILD\",\"claimId\":\"" + claimId + "\",\"state\":\"ROLLED_BACK\","
                + "\"cursor\":10,\"total\":10,\"percent\":100,\"pause\":null,\"unrepaired\":0,\"conflicts\":0,"
                + "\"done\":false,\"partial\":false}";
    }

    @Test
    void theRolledBackDocumentOfTheSameClaimEndsTheUndo() {
        // seen in the real game (p4-undo-003): the last document of an undo is the original BUILD job turned ROLLED_BACK
        BuildChatFlow flow = atUndoing(CLAIM);
        List<Action> actions = flow.progress(rolledBackJson(CLAIM));
        assertEquals(State.IDLE, flow.state());
        assertEquals(List.of(BuildChatFlow.MSG_UNDO_DONE), sayKeys(actions));
    }

    @Test
    void aRolledBackDocumentOfAnotherClaimIsIgnored() {
        BuildChatFlow flow = atUndoing(CLAIM);
        assertEquals(List.of(), flow.progress(rolledBackJson("claim-other")));
        assertEquals(State.UNDOING, flow.state());
    }

    private static BuildChatFlow atUndoing(String claimId) {
        BuildChatFlow flow = atDone(claimId);
        flow.undo();
        flow.undoConfirmed();
        assertEquals(State.UNDOING, flow.state());
        return flow;
    }

    @Test
    void aDoneBuildOffersTheUndoButton() {
        BuildChatFlow flow = atBuilding();
        List<Action> actions =
                flow.progress(progressJson("job-1", "BUILD", CLAIM, 100, true, false, 0, 0));
        assertEquals(State.DONE, flow.state());
        assertEquals(BuildChatFlow.MSG_DONE, actionAt(actions, 0, BuildChatFlow.Say.class).key());
        assertEquals(List.of(ButtonKind.UNDO),
                actionAt(actions, 1, BuildChatFlow.ShowButtons.class).buttons());
    }

    @Test
    void aPartialBuildAlsoOffersTheUndoButton() {
        BuildChatFlow flow = atBuilding();
        List<Action> actions =
                flow.progress(progressJson("job-1", "BUILD", CLAIM, 90, false, true, 2, 1));
        assertEquals(State.DONE, flow.state());
        BuildChatFlow.ShowButtons buttons = actions.stream()
                .filter(a -> a instanceof BuildChatFlow.ShowButtons)
                .map(a -> (BuildChatFlow.ShowButtons) a)
                .findFirst().orElseThrow(() -> new AssertionError("no ShowButtons in " + actions));
        assertEquals(List.of(ButtonKind.UNDO), buttons.buttons());
    }

    @Test
    void aDoneRollbackDoesNotOfferUndo() {
        BuildChatFlow flow = atBuilding();
        List<Action> actions =
                flow.progress(progressJson("job-2", "ROLLBACK", CLAIM, 100, true, false, 0, 0));
        assertEquals(State.DONE, flow.state());
        assertEquals(List.of(BuildChatFlow.MSG_DONE), sayKeys(actions));
        assertTrue(actions.stream().noneMatch(a -> a instanceof BuildChatFlow.ShowButtons),
                "a rollback's own completion must not offer another undo");
    }

    @Test
    void aDoneDocumentWithoutAKindOffersNoUndo() {
        // kind is written by ProgressView only since M5; an older doc proves nothing about the job
        BuildChatFlow flow = atBuilding();
        List<Action> actions = flow.progress(progressJson("job-1", 100, true, false, 0, 0));
        assertEquals(State.DONE, flow.state());
        assertTrue(actions.stream().noneMatch(a -> a instanceof BuildChatFlow.ShowButtons));
    }

    @Test
    void undoIsOnlyAllowedFromDone() {
        BuildChatFlow flow = flow(true);
        assertEquals(List.of(), flow.undo());
        assertEquals(State.IDLE, flow.state());
        BuildChatFlow building = atBuilding();
        assertEquals(List.of(), building.undo());
        assertEquals(State.BUILDING, building.state());
    }

    @Test
    void undoAsksForConfirmationBeforeAnythingIsSent() {
        BuildChatFlow flow = atDone(CLAIM);
        List<Action> actions = flow.undo();
        assertEquals(State.CONFIRM_UNDO, flow.state());
        assertEquals(BuildChatFlow.MSG_UNDO_ASK,
                actionAt(actions, 0, BuildChatFlow.Say.class).key());
        assertEquals(List.of(ButtonKind.UNDO_YES, ButtonKind.UNDO_NO),
                actionAt(actions, 1, BuildChatFlow.ShowButtons.class).buttons());
        assertTrue(actions.stream().noneMatch(a -> a instanceof BuildChatFlow.SendRollback),
                "the rollback goes out only after the child confirms");
    }

    @Test
    void aConfirmedUndoSendsTheClaimId() {
        BuildChatFlow flow = atDone(CLAIM);
        flow.undo();
        List<Action> actions = flow.undoConfirmed();
        assertEquals(State.UNDOING, flow.state());
        BuildChatFlow.SendRollback rollback = actionAt(actions, 0, BuildChatFlow.SendRollback.class);
        assertEquals(CLAIM, rollback.claimId());
        BuildChatFlow.Say say = actionAt(actions, 1, BuildChatFlow.Say.class);
        assertEquals(BuildChatFlow.MSG_UNDOING, say.key());
        assertEquals(List.of("0"), say.args());
        assertTrue(actionAt(actions, 2, BuildChatFlow.ShowButtons.class).buttons().isEmpty(),
                "the button row hides while the rollback runs");
    }

    @Test
    void undoConfirmedIsOnlyAllowedFromConfirmUndo() {
        BuildChatFlow flow = atDone(CLAIM);
        assertEquals(List.of(), flow.undoConfirmed());
        assertEquals(State.DONE, flow.state());
    }

    @Test
    void aCancelledUndoReturnsToDoneWithTheUndoButton() {
        BuildChatFlow flow = atDone(CLAIM);
        flow.undo();
        List<Action> actions = flow.undoCancelled();
        assertEquals(State.DONE, flow.state());
        assertEquals(List.of(ButtonKind.UNDO),
                only(actions, BuildChatFlow.ShowButtons.class).buttons());
    }

    @Test
    void undoCancelledIsOnlyAllowedFromConfirmUndo() {
        BuildChatFlow flow = atDone(CLAIM);
        assertEquals(List.of(), flow.undoCancelled());
        assertEquals(State.DONE, flow.state());
    }

    @Test
    void undoingProgressSpeaksEachNewPercentOnce() {
        BuildChatFlow flow = atUndoing(CLAIM);
        List<Action> at40 =
                flow.progress(progressJson("job-9", "ROLLBACK", CLAIM, 40, false, false, 0, 0));
        BuildChatFlow.Say say = only(at40, BuildChatFlow.Say.class);
        assertEquals(BuildChatFlow.MSG_UNDOING, say.key());
        assertEquals(List.of("40"), say.args());
        assertEquals(List.of(),
                flow.progress(progressJson("job-9", "ROLLBACK", CLAIM, 40, false, false, 0, 0)),
                "the same percent is not repeated");
        List<Action> at60 =
                flow.progress(progressJson("job-9", "ROLLBACK", CLAIM, 60, false, false, 0, 0));
        assertEquals(List.of("60"), only(at60, BuildChatFlow.Say.class).args());
    }

    @Test
    void undoingIgnoresDocumentsThatAreNotTheRollback() {
        BuildChatFlow flow = atUndoing(CLAIM);
        assertEquals(List.of(),
                flow.progress(progressJson("job-1", "BUILD", CLAIM, 50, false, false, 0, 0)));
        assertEquals(List.of(),
                flow.progress(progressJson("job-x", null, null, 50, false, false, 0, 0)));
        assertEquals(State.UNDOING, flow.state());
    }

    @Test
    void aDoneRollbackEndsTheUndoRound() {
        BuildChatFlow flow = atUndoing(CLAIM);
        List<Action> actions =
                flow.progress(progressJson("job-9", "ROLLBACK", CLAIM, 100, true, false, 0, 0));
        assertEquals(State.IDLE, flow.state());
        assertEquals(BuildChatFlow.MSG_UNDO_DONE, only(actions, BuildChatFlow.Say.class).key());
    }

    @Test
    void aPartialRollbackReportsTheLeftoverCount() {
        BuildChatFlow flow = atUndoing(CLAIM);
        List<Action> actions =
                flow.progress(progressJson("job-9", "ROLLBACK", CLAIM, 95, false, true, 2, 1));
        assertEquals(State.IDLE, flow.state());
        BuildChatFlow.Say say = only(actions, BuildChatFlow.Say.class);
        assertEquals(BuildChatFlow.MSG_UNDO_PARTIAL, say.key());
        assertEquals(List.of("3"), say.args(), "unrepaired 2 + conflicts 1 = 3 places left behind");
    }

    @Test
    void aRejectedOfferWhileUndoingReturnsToDone() {
        BuildChatFlow flow = atUndoing(CLAIM);
        List<Action> actions =
                flow.offer(offerJson("REJECTED", null, 0, 0, 0, 0, false, false, List.of()));
        assertEquals(State.DONE, flow.state());
        assertEquals(BuildChatFlow.MSG_UNDO_REFUSED,
                actionAt(actions, 0, BuildChatFlow.Say.class).key());
        assertEquals(List.of(ButtonKind.UNDO),
                actionAt(actions, 1, BuildChatFlow.ShowButtons.class).buttons());
    }

    @Test
    void aNonRejectedOfferWhileUndoingIsIgnored() {
        BuildChatFlow flow = atUndoing(CLAIM);
        assertEquals(List.of(),
                flow.offer(offerJson("OFFERED", "h", 1, 1, 0, 0, false, false, List.of())));
        assertEquals(List.of(), flow.offer("this is not json"));
        assertEquals(State.UNDOING, flow.state());
    }

    @Test
    void aRequestWhileConfirmingOrUndoingIsBusy() {
        BuildChatFlow flow = atDone(CLAIM);
        flow.undo();
        assertEquals(BuildChatFlow.MSG_BUSY,
                only(flow.request("べつの こや"), BuildChatFlow.Say.class).key());
        assertEquals(State.CONFIRM_UNDO, flow.state());
        flow.undoConfirmed();
        assertEquals(BuildChatFlow.MSG_BUSY,
                only(flow.request("べつの こや"), BuildChatFlow.Say.class).key());
        assertEquals(State.UNDOING, flow.state());
    }

    @Test
    void theUndoFlowNeverLeaksTheClaimIdOrAJobIdIntoAChildLine() {
        BuildChatFlow flow = atBuilding();
        List<Action> all = new ArrayList<>(
                flow.progress(progressJson("job-secret", "BUILD", "claim-secret", 100, true, false, 0, 0)));
        all.addAll(flow.undo());
        all.addAll(flow.undoConfirmed());
        all.addAll(flow.progress(progressJson("job-rb", "ROLLBACK", "claim-secret", 50, false, false, 0, 0)));
        all.addAll(flow.progress(progressJson("job-rb", "ROLLBACK", "claim-secret", 100, true, false, 0, 0)));
        for (String text : allSayText(all)) {
            assertFalse(text.contains("claim-secret"), "the claim id leaked: " + text);
            assertFalse(text.contains("job-secret"), "the build job id leaked: " + text);
            assertFalse(text.contains("job-rb"), "the rollback job id leaked: " + text);
        }
    }

    // ---- (i) Task 27b: the missing-materials question and its buttons ---------------------------------

    private static List<ButtonKind> shownButtons(List<Action> actions) {
        return actions.stream()
                .filter(a -> a instanceof BuildChatFlow.ShowButtons)
                .map(a -> ((BuildChatFlow.ShowButtons) a).buttons())
                .findFirst().orElseThrow(() -> new AssertionError("no ShowButtons in " + actions));
    }

    @Test
    void aMaterialsPauseWithDisallowedInventoryAsksOnce() {
        BuildChatFlow flow = atBuilding();
        List<Action> first =
                flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", false));
        assertEquals(List.of("micradrone.build.pause.materials_missing", BuildChatFlow.MSG_ASK_INVENTORY),
                sayKeys(first), "the shortage line stays, then the question");
        assertEquals(List.of(ButtonKind.INVENTORY_YES, ButtonKind.INVENTORY_NO), shownButtons(first));
        assertEquals(List.of(),
                flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", false)),
                "the same pause interval must not ask again");
    }

    @Test
    void theInventoryQuestionReturnsWhenThePauseDoes() {
        BuildChatFlow flow = atBuilding();
        flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", false));
        flow.progress(pausedProgressJson("job-1", CLAIM, 0, null, false)); // chests refilled, then short again
        List<Action> again =
                flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", false));
        assertTrue(sayKeys(again).contains(BuildChatFlow.MSG_ASK_INVENTORY),
                "a pause that cleared and returned is a new interval");
        assertEquals(List.of(ButtonKind.INVENTORY_YES, ButtonKind.INVENTORY_NO), shownButtons(again));
    }

    @Test
    void aMaterialsPauseWithAllowedInventoryDoesNotAsk() {
        BuildChatFlow flow = atBuilding();
        List<Action> actions =
                flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", true));
        assertEquals(List.of("micradrone.build.pause.materials_missing"), sayKeys(actions),
                "the owner already said yes - the plain shortage line is all they hear");
        assertTrue(actions.stream().noneMatch(a -> a instanceof BuildChatFlow.ShowButtons));
    }

    @Test
    void aMaterialsPauseWithoutAClaimIdCannotOfferTheButtons() {
        // the yes-button needs to name the claim: without a claim id there is no question to ask
        BuildChatFlow flow = atBuilding();
        List<Action> actions =
                flow.progress(pausedProgressJson("job-1", null, 0, "MATERIALS_MISSING", false));
        assertEquals(List.of("micradrone.build.pause.materials_missing"), sayKeys(actions));
        assertTrue(actions.stream().noneMatch(a -> a instanceof BuildChatFlow.ShowButtons));
        assertEquals(List.of(), flow.inventoryYes(),
                "no question was shown, so its button cannot permit anything");
    }

    @Test
    void inventoryYesSendsTheClaimPermissionAndHidesTheButtons() {
        BuildChatFlow flow = atBuilding();
        flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", false));
        List<Action> actions = flow.inventoryYes();
        BuildChatFlow.SendMaterials send = actionAt(actions, 0, BuildChatFlow.SendMaterials.class);
        assertEquals(CLAIM, send.claimId());
        assertEquals(Boolean.TRUE, send.allowed());
        assertEquals(List.of(), send.exclude());
        assertEquals(List.of(), send.include());
        assertEquals(BuildChatFlow.MSG_INVENTORY_ON, actionAt(actions, 1, BuildChatFlow.Say.class).key());
        assertTrue(actionAt(actions, 2, BuildChatFlow.ShowButtons.class).buttons().isEmpty());
    }

    @Test
    void inventoryNoSaysNoAndSendsNothing() {
        BuildChatFlow flow = atBuilding();
        flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", false));
        List<Action> actions = flow.inventoryNo();
        assertEquals(BuildChatFlow.MSG_INVENTORY_OFF, actionAt(actions, 0, BuildChatFlow.Say.class).key());
        assertTrue(actionAt(actions, 1, BuildChatFlow.ShowButtons.class).buttons().isEmpty());
        assertTrue(actions.stream().noneMatch(a -> a instanceof BuildChatFlow.SendMaterials),
                "no means no packet - the job stays paused until the chests are refilled");
    }

    @Test
    void inventoryButtonsDoNothingOutsideBuilding() {
        BuildChatFlow idle = flow(true);
        assertEquals(List.of(), idle.inventoryYes());
        assertEquals(List.of(), idle.inventoryNo());
        assertEquals(State.IDLE, idle.state());
        BuildChatFlow offered = atOffered();
        assertEquals(List.of(), offered.inventoryYes());
        assertEquals(List.of(), offered.inventoryNo());
        assertEquals(State.OFFERED, offered.state());
    }

    @Test
    void aSurvivalOfferExplainsTheChestSource() {
        BuildChatFlow flow = atWaitingOffer();
        List<Action> actions = flow.offer(
                offerJson("OFFERED", "hash-1", 5, 20, 0, 0, false, false, List.of(), "SURVIVAL_CONSUME"));
        List<String> keys = sayKeys(actions);
        assertTrue(keys.indexOf(BuildChatFlow.MSG_OFFER_SOURCE) > keys.indexOf(BuildChatFlow.MSG_OFFER),
                "the source line follows the offer line: " + keys);
    }

    @Test
    void aCreativeOfferDoesNotNameAMaterialSource() {
        BuildChatFlow flow = atWaitingOffer();
        List<Action> actions = flow.offer(
                offerJson("OFFERED", "hash-1", 5, 20, 0, 0, false, false, List.of(), "CREATIVE_FREE"));
        assertFalse(sayKeys(actions).contains(BuildChatFlow.MSG_OFFER_SOURCE),
                "creative spends nothing - naming chests would be a lie");
    }

    @Test
    void anOfferWithoutThePolicyFieldNamesNoSource() {
        // an older doc (or a refused submit) never claimed a material source, so neither does the panel
        BuildChatFlow flow = atWaitingOffer();
        List<Action> actions =
                flow.offer(offerJson("OFFERED", "hash-1", 5, 20, 0, 0, false, false, List.of()));
        assertFalse(sayKeys(actions).contains(BuildChatFlow.MSG_OFFER_SOURCE));
    }

    // ---- (j) Task 27b (追加): the materials directive a reply may carry -------------------------------

    /** A reply with a plan block plus a {@code ```materials} frame holding {@code materialsJson}. */
    private static StageResult aiOkWithMaterials(String says, String materialsJson) {
        return aiOk(says + "\n```json\n{\"ops\":[]}\n```\n```materials\n" + materialsJson + "\n```");
    }

    private static BuildChatFlow.SendMaterials sendMaterials(List<Action> actions) {
        return actions.stream()
                .filter(a -> a instanceof BuildChatFlow.SendMaterials)
                .map(a -> (BuildChatFlow.SendMaterials) a)
                .findFirst().orElseThrow(() -> new AssertionError("no SendMaterials in " + actions));
    }

    @Test
    void aMaterialsDirectiveIsConfirmedNowAndSentOnceTheClaimExists() {
        BuildChatFlow flow = flow(true);
        flow.request("ダイヤは つかわない こやを たてて");
        List<Action> reply = flow.aiReply(aiOkWithMaterials("ダイヤなしで つくるよ",
                "{\"materials\":{\"inventory\":true,\"exclude\":[\"minecraft:diamond\"]}}"));
        assertEquals(State.WAITING_OFFER, flow.state());
        assertTrue(sayKeys(reply).contains(BuildChatFlow.MSG_MATERIALS_CHANGED),
                "an accepted directive always confirms itself to the child");
        BuildChatFlow.Say excluded = reply.stream()
                .filter(a -> a instanceof BuildChatFlow.Say s
                        && s.key().equals(BuildChatFlow.MSG_MATERIALS_EXCLUDED))
                .map(a -> (BuildChatFlow.Say) a).findFirst().orElseThrow();
        assertEquals(List.of("minecraft:diamond"), excluded.args(),
                "the item goes in as the arg - the screen turns it into the item's name");
        assertTrue(reply.stream().noneMatch(a -> a instanceof BuildChatFlow.SendMaterials),
                "no claim exists yet - the directive waits for the build's first document");
        flow.offer(offerJson("OFFERED", "h", 5, 20, 0, 0, false, false, List.of()));
        flow.approve();
        List<Action> firstDoc =
                flow.progress(progressJson("job-1", "BUILD", CLAIM, 5, false, false, 0, 0));
        BuildChatFlow.SendMaterials send = sendMaterials(firstDoc);
        assertEquals(CLAIM, send.claimId(), "the approved build's claim, not a remembered id");
        assertEquals(Boolean.TRUE, send.allowed());
        assertEquals(List.of("minecraft:diamond"), send.exclude());
        assertEquals(List.of(), send.include());
        assertTrue(flow.progress(progressJson("job-1", "BUILD", CLAIM, 10, false, false, 0, 0)).stream()
                .noneMatch(a -> a instanceof BuildChatFlow.SendMaterials),
                "the pending directive goes out exactly once");
    }

    @Test
    void anIncludeOnlyDirectiveStillConfirmsAndSends() {
        BuildChatFlow flow = flow(true);
        flow.request("エメラルドも つかって いいよ");
        List<Action> reply = flow.aiReply(aiOkWithMaterials("エメラルドも つかうよ",
                "{\"materials\":{\"include\":[\"minecraft:emerald\"]}}"));
        assertTrue(sayKeys(reply).contains(BuildChatFlow.MSG_MATERIALS_CHANGED));
        assertTrue(sayKeys(reply).contains(BuildChatFlow.MSG_MATERIALS_INCLUDED));
        flow.offer(offerJson("OFFERED", "h", 5, 20, 0, 0, false, false, List.of()));
        flow.approve();
        BuildChatFlow.SendMaterials send = sendMaterials(
                flow.progress(progressJson("job-1", "BUILD", CLAIM, 5, false, false, 0, 0)));
        assertNull(send.allowed(), "an absent inventory field asks for no change");
        assertEquals(List.of("minecraft:emerald"), send.include());
    }

    @Test
    void anInvalidDirectiveSendsNothingAndSaysUnclear() {
        BuildChatFlow flow = flow(true);
        flow.request("なんか つかわないで");
        List<Action> reply = flow.aiReply(aiOkWithMaterials("こやを つくるよ",
                "{\"materials\":{\"exclude\":[\"minecraft:command_block\"]}}"));
        assertTrue(sayKeys(reply).contains(BuildChatFlow.MSG_MATERIALS_UNCLEAR),
                "command_block is not in the test catalog - the child hears that it was not understood");
        assertTrue(reply.stream().noneMatch(a -> a instanceof BuildChatFlow.SendMaterials));
        flow.offer(offerJson("OFFERED", "h", 5, 20, 0, 0, false, false, List.of()));
        flow.approve();
        assertTrue(flow.progress(progressJson("job-1", "BUILD", CLAIM, 5, false, false, 0, 0)).stream()
                .noneMatch(a -> a instanceof BuildChatFlow.SendMaterials),
                "a dropped directive is never sent, not even partially");
    }

    @Test
    void anEmptyDirectiveIsNeitherConfirmedNorSent() {
        BuildChatFlow flow = flow(true);
        flow.request("こやを たてて");
        List<Action> reply = flow.aiReply(aiOkWithMaterials("こやを つくるよ", "{\"materials\":{}}"));
        assertFalse(sayKeys(reply).contains(BuildChatFlow.MSG_MATERIALS_CHANGED),
                "nothing changed, so nothing is confirmed");
        flow.offer(offerJson("OFFERED", "h", 5, 20, 0, 0, false, false, List.of()));
        flow.approve();
        assertTrue(flow.progress(progressJson("job-1", "BUILD", CLAIM, 5, false, false, 0, 0)).stream()
                .noneMatch(a -> a instanceof BuildChatFlow.SendMaterials));
    }

    @Test
    void aMaterialsWordWhileBuildingStaysBusy() {
        BuildChatFlow flow = atBuilding();
        assertEquals(BuildChatFlow.MSG_BUSY,
                only(flow.request("ダイヤは つかわないで"), BuildChatFlow.Say.class).key());
        assertEquals(State.BUILDING, flow.state());
    }

    @Test
    void theMaterialsControlsNeverLeakAClaimOrJobIdIntoAChildLine() {
        BuildChatFlow flow = atBuilding();
        List<Action> all = new ArrayList<>(
                flow.progress(pausedProgressJson("job-secret", "claim-secret", 0, "MATERIALS_MISSING", false)));
        all.addAll(flow.inventoryYes());
        all.addAll(flow.progress(pausedProgressJson("job-secret", "claim-secret", 0, null, true)));
        for (String text : allSayText(all)) {
            assertFalse(text.contains("claim-secret"), "the claim id leaked: " + text);
            assertFalse(text.contains("job-secret"), "the job id leaked: " + text);
        }
    }

    // ---- (k) Task 27e: a materials request the server refused -------------------------------------

    /** The offer doc the network answers a refused materials packet with: bare REJECTED, no issues. */
    private static String rejectedOfferJson() {
        return offerJson("REJECTED", null, 0, 0, 0, 0, false, false, List.of());
    }

    @Test
    void aRejectedOfferAfterMaterialsWereSentTellsTheChild() {
        BuildChatFlow flow = atBuilding();
        flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", false));
        List<Action> sent = flow.inventoryYes();
        assertTrue(sent.stream().anyMatch(a -> a instanceof BuildChatFlow.SendMaterials));
        List<Action> actions = flow.offer(rejectedOfferJson());
        assertEquals(List.of(BuildChatFlow.MSG_MATERIALS_REFUSED), sayKeys(actions),
                "the refusal of the packet just sent is the child's line");
        assertEquals(List.of(), only(actions, BuildChatFlow.Say.class).args(),
                "the refusal carries no ids and no technical text");
        assertEquals(State.BUILDING, flow.state(), "the build itself goes on - only the ask failed");
    }

    @Test
    void aRejectedOfferWithoutASentMaterialsAskStaysSilent() {
        BuildChatFlow flow = atBuilding();
        flow.progress(progressJson("job-1", "BUILD", CLAIM, 5, false, false, 0, 0));
        assertEquals(List.of(), flow.offer(rejectedOfferJson()),
                "no request went out, so a stray refusal is not the child's business");
        assertEquals(State.BUILDING, flow.state());
    }

    @Test
    void aRejectedOfferAnswersAParkedDirectiveToo() {
        // the directive parked at the AI reply goes out with the build's first document (27b)
        BuildChatFlow flow = flow(true);
        flow.request("ダイヤは つかわない こやを たてて");
        flow.aiReply(aiOkWithMaterials("ダイヤなしで つくるよ",
                "{\"materials\":{\"exclude\":[\"minecraft:diamond\"]}}"));
        flow.offer(offerJson("OFFERED", "h", 5, 20, 0, 0, false, false, List.of()));
        flow.approve();
        List<Action> firstDoc =
                flow.progress(progressJson("job-1", "BUILD", CLAIM, 5, false, false, 0, 0));
        assertTrue(firstDoc.stream().anyMatch(a -> a instanceof BuildChatFlow.SendMaterials),
                "the parked materials ask leaves with the first progress document");
        assertEquals(List.of(BuildChatFlow.MSG_MATERIALS_REFUSED),
                sayKeys(flow.offer(rejectedOfferJson())));
    }

    @Test
    void aFailedOfferIsNotTheMaterialsRefusal() {
        BuildChatFlow flow = atBuilding();
        flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", false));
        flow.inventoryYes();
        // FAILED while the job id is known is a job document the panel ignores, not the answer to
        // the materials packet - only a bare REJECTED is
        assertEquals(List.of(), flow.offer(
                offerJson("FAILED", null, 0, 0, 0, 0, false, false, List.of())));
    }

    @Test
    void aRefusedMaterialsAskMustBeSentAgainToBeRefusedAgain() {
        BuildChatFlow flow = atBuilding();
        flow.progress(pausedProgressJson("job-1", CLAIM, 0, "MATERIALS_MISSING", false));
        flow.inventoryYes();
        flow.offer(rejectedOfferJson());
        assertEquals(List.of(), flow.offer(rejectedOfferJson()),
                "the refusal was already told; nothing else was sent to answer");
    }

    @Test
    void theNewMaterialLinesStayFreeOfCommandsAndIds() throws java.io.IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> ja = (Map<String, Object>) MiniJson.parse(java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/assets/micradrone/lang/ja_jp.json"),
                java.nio.charset.StandardCharsets.UTF_8));
        for (String key : List.of(BuildChatFlow.MSG_ASK_INVENTORY, BuildChatFlow.MSG_INVENTORY_ON,
                BuildChatFlow.MSG_INVENTORY_OFF, BuildChatFlow.MSG_OFFER_SOURCE,
                BuildChatFlow.MSG_MATERIALS_CHANGED, BuildChatFlow.MSG_MATERIALS_UNCLEAR,
                BuildChatFlow.MSG_MATERIALS_REFUSED)) {
            String text = (String) ja.get(key);
            assertTrue(text != null, "missing ja text for " + key);
            assertFalse(text.contains("/micradrone") || text.contains("%1$s")
                    || text.contains("recover") || text.contains("resume"), key + ": " + text);
        }
        for (String key : List.of(BuildChatFlow.MSG_MATERIALS_EXCLUDED, BuildChatFlow.MSG_MATERIALS_INCLUDED)) {
            String text = (String) ja.get(key);
            assertTrue(text != null && text.contains("%1$s"),
                    "the item's display name goes in as the arg: " + key);
            assertFalse(text.contains("/micradrone") || text.contains("%2$s")
                    || text.contains("recover") || text.contains("resume"), key + ": " + text);
        }
    }
}
