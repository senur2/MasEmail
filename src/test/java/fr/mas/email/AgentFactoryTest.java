package fr.mas.email;

import fr.mas.email.agent.*;
import fr.mas.email.contract.*;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.result.*;
import fr.mas.email.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AgentFactoryTest {
    @TempDir Path directory;
    private final FakeLlmGateway gateway = new FakeLlmGateway(new JsonCodec());

    private AgentFactory factory() throws Exception {
        return new AgentFactory(gateway, new FileWorldStore(directory, new JsonCodec()));
    }

    static AgentConfig config(String id, AgentRole role) {
        return new AgentConfig(id, role, id + " specialty", List.of("Answer accurately"), List.of("Do not invent facts"));
    }

    static WriterInput input() {
        return new WriterInput(TestData.analysis("fr", "REQUEST", List.of()), List.of(), List.of());
    }

    @Test
    void createsDifferentWriterProfilesFromTheSameImplementation() throws Exception {
        var factory = factory();
        var research = factory.createWriter(config("ResearchWriter", AgentRole.WRITER), AgentBehavior.unconfigured());
        var student = factory.createWriter(config("AcademicWriter", AgentRole.WRITER), AgentBehavior.unconfigured());
        assertEquals(research.getClass(), student.getClass());
        assertNotEquals(research.id(), student.id());
        assertNotEquals(research.configuration().specialization(), student.configuration().specialization());
        assertEquals(ErrorCode.NOT_IMPLEMENTED,
                ((ErrorPlan<?>) research.execute(input(), TaskContext.start())).code());
        assertEquals(0, gateway.callCount());
    }

    @Test
    void rejectsDuplicateIdsWrongRolesAndInvalidIdentifiers() throws Exception {
        var factory = factory();
        factory.createWriter(config("Writer", AgentRole.WRITER));
        assertThrows(IllegalArgumentException.class, () -> factory.createWriter(config("Writer", AgentRole.WRITER)));
        assertThrows(IllegalArgumentException.class, () -> factory.createWriter(config("Parser", AgentRole.PARSER)));
        assertThrows(IllegalArgumentException.class, () -> config("../Writer", AgentRole.WRITER));
        assertNotNull(factory.createParser(config("Parser", AgentRole.PARSER), AgentBehavior.unconfigured()));
    }

    @Test
    void createsEverySkeletonWithoutInventingBusinessBehavior() throws Exception {
        var factory = factory();
        var task = TaskContext.start();
        var parser = factory.createParser(config("Parser", AgentRole.PARSER), AgentBehavior.unconfigured());
        var decision = factory.createDecision(config("Decision", AgentRole.DECISION), AgentBehavior.unconfigured());
        var scribe = factory.createScribe(config("Scribe", AgentRole.SCRIBE), AgentBehavior.unconfigured());
        CorrectingAgent correcting = factory.createCorrecting(
                config("Correcting", AgentRole.CORRECTING), AgentBehavior.unconfigured());
        assertInstanceOf(ErrorPlan.class, parser.execute("Email", task));
        assertInstanceOf(ErrorPlan.class, decision.execute(new DecisionInput(input().analysis(), List.of()), task));
        assertInstanceOf(ErrorPlan.class, scribe.execute(
                TestData.plan(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of()), task));
        assertInstanceOf(ErrorPlan.class, correcting.execute("Feedback", task));
        assertEquals(0, gateway.callCount());
    }

    @Test
    void writerCanReadItsBoundMiniWorldButCannotWriteKnowledge() throws Exception {
        var factory = factory();
        var writer = factory.createWriter(config("Writer", AgentRole.WRITER), (input, context) -> {
            assertSame(gateway, context.llm());
            assertTrue(context.miniWorld().load().isEmpty());
            assertThrows(SecurityException.class,
                    () -> context.miniWorld().save(new MiniWorld("Writer", Map.of("rule", "private"))));
            assertThrows(SecurityException.class,
                    () -> context.miniWorld().save(MiniWorld.empty("AnotherWriter")));
            return new Success<>(TestData.plan(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of()));
        });
        assertInstanceOf(Success.class, writer.execute(input(), TaskContext.start()));
    }

    @Test
    void nonWriterHasNoWorldWritePermissionAndFailuresAreConverted() throws Exception {
        var factory = factory();
        var parser = factory.createParser(config("Parser", AgentRole.PARSER), (input, context) -> {
            context.miniWorld().save(MiniWorld.empty("Parser"));
            return new Success<>(TestData.analysis("fr", "REQUEST", List.of()));
        });
        assertEquals(ErrorCode.PERMISSION_DENIED,
                ((ErrorPlan<?>) parser.execute("Email", TaskContext.start())).code());
        var writer = factory.createWriter(config("Writer", AgentRole.WRITER),
                (input, context) -> { throw new java.io.IOException("private path"); });
        var error = (ErrorPlan<?>) writer.execute(input(), TaskContext.start());
        assertEquals(ErrorCode.FILE_ACCESS_ERROR, error.code());
        assertFalse(error.toString().contains("private path"));
    }

    @Test
    void behaviorCannotSilentlyReturnNull() throws Exception {
        var writer = factory().createWriter(config("Writer", AgentRole.WRITER), (input, context) -> null);
        assertEquals(ErrorCode.NO_RESULT,
                ((ErrorPlan<?>) writer.execute(input(), TaskContext.start())).code());
    }
}
