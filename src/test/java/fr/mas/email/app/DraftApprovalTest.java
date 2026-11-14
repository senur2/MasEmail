package fr.mas.email.app;

import fr.mas.email.contract.*;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.result.*;
import fr.mas.email.world.FileWorldStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DraftApprovalTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();

    @Test
    void approvalRecordsTheExactDraftAndSurvivesControllerRecreation() throws Exception {
        var runner = started();
        var review = runner.review(directory);
        assertTrue(review.canApprove());
        assertFalse(review.approved());
        var approval = runner.approve(review.draft(), directory);
        var restored = new PipelineRunner(json).review(directory);
        assertTrue(restored.approved());
        assertFalse(restored.canApprove());
        assertTrue(restored.canCorrect());
        assertEquals(review.draft(), approval.draft());
        assertEquals(DraftResponse.Status.DRAFT, approval.draft().status());
        assertEquals(approval, new FileWorldStore(directory, json).draftApproval().load().orElseThrow());
        assertTrue(Files.readString(directory.resolve("agents.log")).contains("VALIDATION DU BROUILLON"));
        assertEquals(approval, runner.approve(review.draft(), directory));
    }

    @Test
    void correctedBodyInvalidatesApprovalEvenWithTheSamePlanId() throws Exception {
        var runner = started();
        var original = runner.review(directory).draft();
        runner.approve(original, directory);
        var plan = new CorrectionPlan(CorrectionPlan.Target.SCRIBE_AGENT, CorrectionPlan.Type.ADJUST_STYLE,
                false, "Tutoie-le", List.of(), "Style");
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(plan))
                .enqueueJson(json.write(new DraftContent(original.subject(), "Salut,\n\n" + original.body())));
        assertInstanceOf(Success.class, runner.correct("Tutoie-le", gateway, directory, progress -> {}));
        var revised = runner.review(directory);
        assertEquals(original.planId(), revised.draft().planId());
        assertFalse(revised.approved());
        assertTrue(revised.canApprove());
        assertThrows(IllegalStateException.class, () -> runner.approve(original, directory));
        runner.approve(revised.draft(), directory);
        assertTrue(runner.review(directory).approved());
    }

    @Test
    void pendingMemoryValidationPreventsApprovingTheOldDraft() throws Exception {
        var runner = started();
        var draft = runner.review(directory).draft();
        var gateway = new FakeLlmGateway(json).enqueueJson(fr.mas.email.GeneratedCorrectionTest.REPORTED_OUTPUT);
        assertInstanceOf(DiscussionPlan.class, runner.correct("Nouvelle date", gateway, directory, progress -> {}));
        assertFalse(runner.review(directory).canApprove());
        assertThrows(IllegalStateException.class, () -> runner.approve(draft, directory));
        assertFalse(Files.exists(directory.resolve("draft-approval.json")));
    }

    @Test
    void failedCorrectionCanBeExplicitlyDiscardedByApprovingTheExistingDraft() throws Exception {
        var runner = started();
        var draft = runner.review(directory).draft();
        runner.approve(draft, directory);
        var gateway = new FakeLlmGateway(json).enqueueJson("{");
        assertInstanceOf(ErrorPlan.class, runner.correct("Correction", gateway, directory, progress -> {}));
        assertFalse(runner.review(directory).approved());
        runner.approve(draft, directory);
        assertTrue(runner.review(directory).approved());
    }

    @Test
    void threeCorrectionsStillAllowApprovalWithoutAnotherLlmCall() throws Exception {
        var runner = started();
        for (int i = 0; i < 3; i++) {
            assertInstanceOf(Success.class, runner.correct("Style", DemoScenario.correction(json),
                    directory, progress -> {}));
        }
        assertFalse(runner.review(directory).canCorrect());
        assertTrue(runner.review(directory).canApprove());
        runner.approve(runner.review(directory).draft(), directory);
        assertTrue(runner.review(directory).approved());
    }

    private PipelineRunner started() throws Exception {
        var runner = new PipelineRunner(json);
        assertInstanceOf(Success.class, runner.run(DemoScenario.EMAIL, DemoScenario.gateway(json), "simulation",
                directory, progress -> {}));
        return runner;
    }
}
