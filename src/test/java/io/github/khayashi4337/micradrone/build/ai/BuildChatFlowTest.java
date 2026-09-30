package io.github.khayashi4337.micradrone.build.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.ai.BuildChatFlow.Action;
import io.github.khayashi4337.micradrone.build.ai.BuildChatFlow.ButtonKind;
import io.github.khayashi4337.micradrone.build.ai.BuildChatFlow.State;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildChatFlowTest {
    private static final BuildChatFlow.PromptParts PARTS =
            new BuildChatFlow.PromptParts("{\"ops\":[]}", "micra:structure(...)", "minecraft:stone");

    private static BuildChatFlow flow(boolean consentGiven) {
        return new BuildChatFlow(consentGiven, PARTS);
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
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("jobId", jobId);
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
}
