package cn.watsontech.snapagent.core.tool;

import cn.watsontech.snapagent.core.agent.SystemPromptExtender;
import cn.watsontech.snapagent.core.conversation.ConversationStore;
import cn.watsontech.snapagent.core.workflow.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Tests for backward-compatible methods added to PluginRegistry and
 * ToolDispatcher for 1.x compatibility.
 */
class CompatibilityShimsTest {

    private InMemoryPluginRegistry registry;
    private ToolCallback echoCallback;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPluginRegistry();
        echoCallback = new ToolCallback() {
            @Override
            public String getName() { return "echo"; }
            @Override
            public String getJsonSchema() {
                return "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}";
            }
            @Override
            public ToolResult execute(Map<String, Object> args, Object ctx) {
                return ToolResult.success("echo: " + args.get("text"), 1, 0L);
            }
        };
    }

    @Test
    void pluginRegistryListAliasWorks() {
        PluginDescriptor desc = new PluginDescriptor(
                "test-plugin", "echo", "Echo", "1.0.0", "test",
                true, true, true, new ToolCallback[]{echoCallback},
                null, null, null);
        registry.register(desc);

        // list() is the compatibility alias for listPlugins()
        List<PluginDescriptor> list = registry.list();
        assertEquals(1, list.size());
        assertEquals("test-plugin", list.get(0).getPluginId());
    }

    @Test
    void pluginRegistryEnableDisableWorks() {
        PluginDescriptor desc = new PluginDescriptor(
                "test-plugin", "echo", "Echo", "1.0.0", "test",
                true, true, true, new ToolCallback[]{echoCallback},
                null, null, null);
        registry.register(desc);

        assertTrue(registry.getPlugin("test-plugin").isEnabled());

        registry.disable("test-plugin");
        assertFalse(registry.getPlugin("test-plugin").isEnabled());

        registry.enable("test-plugin");
        assertTrue(registry.getPlugin("test-plugin").isEnabled());
    }

    @Test
    void pluginRegistrySetDefaultWorks() {
        PluginDescriptor desc1 = new PluginDescriptor(
                "plugin-a", "echo", "Echo A", "1.0.0", "test",
                true, true, true, new ToolCallback[]{echoCallback},
                null, null, null);
        PluginDescriptor desc2 = new PluginDescriptor(
                "plugin-b", "echo", "Echo B", "1.0.0", "test",
                false, true, false, new ToolCallback[]{echoCallback},
                null, null, null);
        registry.register(desc1);
        registry.register(desc2);

        assertTrue(registry.getPlugin("plugin-a").isDefault());
        assertFalse(registry.getPlugin("plugin-b").isDefault());

        registry.setDefault("echo", "plugin-b");

        assertFalse(registry.getPlugin("plugin-a").isDefault());
        assertTrue(registry.getPlugin("plugin-b").isDefault());
    }

    @Test
    void toolDispatcherImplementsToolCallbackRegistry() {
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 10000);

        assertTrue(dispatcher instanceof ToolCallbackRegistry);
    }

    @Test
    void toolDispatcherFindReturnsCallback() {
        PluginDescriptor desc = new PluginDescriptor(
                "test-plugin", "echo", "Echo", "1.0.0", "test",
                true, true, true, new ToolCallback[]{echoCallback},
                null, null, null);
        registry.register(desc);

        ToolDispatcher dispatcher = new ToolDispatcher(registry, 10000);
        ToolCallback found = dispatcher.find("echo");
        assertNotNull(found);
        assertEquals("echo", found.getName());
    }

    @Test
    void toolDispatcherFindReturnsNullForUnknown() {
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 10000);
        assertNull(dispatcher.find("nonexistent"));
    }

    @Test
    void toolDispatcherGetAllReturnsAllCallbacks() {
        PluginDescriptor desc = new PluginDescriptor(
                "test-plugin", "echo", "Echo", "1.0.0", "test",
                true, true, true, new ToolCallback[]{echoCallback},
                null, null, null);
        registry.register(desc);

        ToolDispatcher dispatcher = new ToolDispatcher(registry, 10000);
        List<ToolCallback> all = dispatcher.getAll();
        assertEquals(1, all.size());
        assertEquals("echo", all.get(0).getName());
    }

    @Test
    void pluginDescriptorGetProviderWrapsFirstCallback() {
        PluginDescriptor desc = new PluginDescriptor(
                "test-plugin", "echo", "Echo", "1.0.0", "test",
                true, true, true, new ToolCallback[]{echoCallback},
                null, null, null);

        ToolProvider provider = desc.getProvider();
        assertNotNull(provider);
        assertEquals("echo", provider.name());
    }

    @Test
    void pluginDescriptorGetProviderReturnsNullForEmptyCallbacks() {
        PluginDescriptor desc = new PluginDescriptor(
                "test-plugin", "echo", "Echo", "1.0.0", "test",
                true, true, true, new ToolCallback[0],
                null, null, null);

        assertNull(desc.getProvider());
    }

    @Test
    void toolContextWithAuditCallbackPreservesFields() {
        AuditCallback audit = (toolName, args, result) -> { };
        ToolContext ctx = new ToolContext("task-1", "user-1", audit);

        assertEquals("task-1", ctx.getTaskId());
        assertEquals("user-1", ctx.getUserId());
        assertNotNull(ctx.getAuditCallback());
    }

    @Test
    void toolContextWithPluginContextWorks() {
        AuditCallback audit = (toolName, args, result) -> { };
        Map<String, String> overrides = new HashMap<>();
        overrides.put("tool1", "plugin-a");
        Object pluginCtx = new Object();

        ToolContext ctx = new ToolContext("task-1", "user-1", audit, overrides, pluginCtx);

        assertEquals(pluginCtx, ctx.getPluginContext());
        assertEquals("plugin-a", ctx.getPluginOverrides().get("tool1"));

        ToolContext newCtx = ctx.withPluginContext("new-context");
        assertEquals("new-context", newCtx.getPluginContext());
        assertEquals(ctx.getTaskId(), newCtx.getTaskId());
    }

    @Test
    void workflowStatusEnumValuesExist() {
        assertEquals("COMPLETED", WorkflowStatus.COMPLETED.name());
        assertEquals("ABORTED", WorkflowStatus.ABORTED.name());
        assertEquals("FAILED", WorkflowStatus.FAILED.name());
    }

    @Test
    void workflowResultSuccessFactoryWorks() {
        Map<String, StepResult> steps = new LinkedHashMap<>();
        steps.put("step1", new StepResult("step1", "task-1", "SUCCEEDED", "done"));

        WorkflowResult result = WorkflowResult.success("test-workflow", steps, 100L);

        assertEquals("test-workflow", result.getWorkflowName());
        assertTrue(result.isSuccess());
        assertEquals(WorkflowStatus.COMPLETED, result.getStatus());
        assertEquals(100L, result.getDurationMs());
        assertEquals(1, result.getStepResults().size());
    }

    @Test
    void workflowResultFailureFactoryWorks() {
        Map<String, StepResult> steps = new LinkedHashMap<>();
        WorkflowResult result = WorkflowResult.failure("test-wf", "failed-step", "error msg", steps, 50L);

        assertFalse(result.isSuccess());
        assertEquals(WorkflowStatus.FAILED, result.getStatus());
        assertEquals("failed-step", result.getFailedStep());
        assertEquals("error msg", result.getErrorMessage());
    }

    @Test
    void workflowStepOnFailureDefaultsToStop() {
        WorkflowStep step = new WorkflowStep("s1", "skill1", null, null, null);

        assertEquals(WorkflowStep.STOP, step.getOnFailure());
    }

    @Test
    void workflowDefinitionIsImmutable() {
        List<WorkflowStep> steps = new ArrayList<>();
        steps.add(new WorkflowStep("s1", "skill1", null, null, null));

        WorkflowDefinition def = new WorkflowDefinition("wf1", "desc", steps);

        assertEquals("wf1", def.getName());
        assertEquals(1, def.getSteps().size());

        // Verify immutability
        assertThrows(UnsupportedOperationException.class, () -> {
            def.getSteps().add(new WorkflowStep("s2", "skill2", null, null, null));
        });
    }

    @Test
    void conversationStoreInterfaceExists() {
        // Verify the interface exists and is loadable
        assertNotNull(ConversationStore.class);
    }

    @Test
    void toolPluginAnnotationExists() {
        // Verify the annotation is loadable
        assertNotNull(ToolPluginAnnotation.class);
        assertEquals("cn.watsontech.snapagent.core.tool.ToolPluginAnnotation",
                ToolPluginAnnotation.class.getName());
    }

    @Test
    void systemPromptExtenderInterfaceExists() {
        assertNotNull(SystemPromptExtender.class);
    }
}
