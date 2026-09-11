package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AstCodeGraphBuilder}.
 *
 * <p>Each test writes Java source files to a {@link TempDir} and verifies
 * the resulting code graph nodes and edges.
 */
class AstCodeGraphBuilderTest {

    @TempDir
    Path tempDir;

    private CodePathGuard makeGuard(Path root) {
        return new CodePathGuard(root.toString(),
                Arrays.asList(".java", ".xml"),
                500, 512 * 1024);
    }

    private void writeFile(Path dir, String fileName, String content) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve(fileName), content.getBytes());
    }

    // ---- Class declaration ----

    @Test
    void shouldParseClassDeclaration() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Foo.java",
                "package com.test;\n\n"
                + "public class Foo {\n"
                + "    private String name;\n"
                + "    public String getName() {\n"
                + "        return name;\n"
                + "    }\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Verify CLASS node with correct FQCN
        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS
                && n.getName().equals("Foo")
                && n.getClassName().equals("com.test.Foo"));
    }

    // ---- Method declaration ----

    @Test
    void shouldParseMethodDeclaration() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Calculator.java",
                "package com.test;\n\n"
                + "public class Calculator {\n"
                + "    public int add(int a, int b) {\n"
                + "        return a + b;\n"
                + "    }\n"
                + "    public String greet(String name) {\n"
                + "        return \"Hello \" + name;\n"
                + "    }\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Verify METHOD node with return type and params
        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.METHOD
                && n.getName().equals("add")
                && n.getReturnType().equals("int")
                && n.getId().contains("add(int, int)"));

        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.METHOD
                && n.getName().equals("greet")
                && n.getReturnType().equals("String")
                && n.getId().contains("greet(String)"));
    }

    // ---- Field declaration ----

    @Test
    void shouldParseFieldDeclaration() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Person.java",
                "package com.test;\n\n"
                + "public class Person {\n"
                + "    private String name;\n"
                + "    private int age;\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Verify FIELD nodes with types
        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.FIELD
                && n.getName().equals("name")
                && n.getReturnType().equals("String"));

        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.FIELD
                && n.getName().equals("age")
                && n.getReturnType().equals("int"));
    }

    // ---- Extends edge ----

    @Test
    void shouldExtractExtendsEdge() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Bar.java",
                "package com.test;\n\n"
                + "public class Bar extends Foo {\n"
                + "    public void run() {\n"
                + "    }\n"
                + "}\n");
        writeFile(srcDir, "Foo.java",
                "package com.test;\n\n"
                + "public class Foo {\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Verify EXTENDS edge from Bar to Foo
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.EXTENDS
                && e.getFromId().equals("com.test.Bar")
                && e.getToId().equals("com.test.Foo"));
    }

    // ---- Implements edge ----

    @Test
    void shouldExtractImplementsEdge() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Task.java",
                "package com.test;\n\n"
                + "public class Task implements Runnable {\n"
                + "    public void run() {\n"
                + "    }\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Verify IMPLEMENTS edge from Task to Runnable
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.IMPLEMENTS
                && e.getFromId().equals("com.test.Task")
                && e.getToId().equals("java.lang.Runnable"));
    }

    // ---- Method calls ----

    @Test
    void shouldExtractMethodCalls() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Caller.java",
                "package com.test;\n\n"
                + "public class Caller {\n"
                + "    public void doWork() {\n"
                + "        Helper helper = new Helper();\n"
                + "        helper.execute();\n"
                + "    }\n"
                + "}\n");
        writeFile(srcDir, "Helper.java",
                "package com.test;\n\n"
                + "public class Helper {\n"
                + "    public void execute() {\n"
                + "    }\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Verify CALLS edge from doWork to Helper.execute(*)
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.CALLS
                && e.getToId().contains("execute"));
    }

    // ---- Comments: method calls in comments should NOT produce edges ----

    @Test
    void shouldNotIncludeCallsInComments() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Commenter.java",
                "package com.test;\n\n"
                + "public class Commenter {\n"
                + "    public void doWork() {\n"
                + "        // helper.execute();\n"
                + "        /* helper.anotherCall(); */\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // No CALLS edges should exist — the calls are only in comments
        assertThat(graph.getEdges()).noneMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.CALLS);
    }

    // ---- Lambda/Stream calls ----

    @Test
    void shouldHandleLambdaCalls() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Streamer.java",
                "package com.test;\n\n"
                + "import java.util.List;\n"
                + "import java.util.ArrayList;\n\n"
                + "public class Streamer {\n"
                + "    public void process(List<String> items) {\n"
                + "        items.stream().filter(x -> x.isActive()).count();\n"
                + "    }\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Should produce at least one CALLS edge (from filter or count)
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.CALLS);
    }

    // ---- Overloaded methods ----

    @Test
    void shouldHandleOverloadedMethods() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Overloader.java",
                "package com.test;\n\n"
                + "public class Overloader {\n"
                + "    public void process(int value) {\n"
                + "    }\n"
                + "    public void process(String value) {\n"
                + "    }\n"
                + "    public void process(int a, int b) {\n"
                + "    }\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // All three overloaded methods should be separate METHOD nodes
        List<CodeGraphNode> methods = graph.getNodes();
        assertThat(methods).filteredOn(n ->
                n.getType() == CodeGraphNode.NodeType.METHOD && n.getName().equals("process"))
                .hasSize(3);

        // Each should have a distinct signature in its ID
        assertThat(methods).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.METHOD
                && n.getId().contains("process(int)"));
        assertThat(methods).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.METHOD
                && n.getId().contains("process(String)"));
        assertThat(methods).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.METHOD
                && n.getId().contains("process(int, int)"));
    }

    // ---- Generics erasure ----

    @Test
    void shouldHandleGenericsErasure() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "GenericHolder.java",
                "package com.test;\n\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n\n"
                + "public class GenericHolder {\n"
                + "    private List<String> names;\n"
                + "    private Map<String, Integer> counts;\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Field type should be erased: List<String> → List, Map<...> → Map
        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.FIELD
                && n.getName().equals("names")
                && n.getReturnType().equals("List"));

        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.FIELD
                && n.getName().equals("counts")
                && n.getReturnType().equals("Map"));
    }

    // ---- Scan packages filter ----

    @Test
    void shouldRespectScanPackages() throws IOException {
        Path pkgA = tempDir.resolve("src/com/test/a");
        Path pkgB = tempDir.resolve("src/com/test/b");
        writeFile(pkgA, "ClassA.java",
                "package com.test.a;\npublic class ClassA {}\n");
        writeFile(pkgB, "ClassB.java",
                "package com.test.b;\npublic class ClassB {}\n");

        // Only scan com.test.a
        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")),
                Collections.singletonList("com.test.a"));
        CodeGraph graph = builder.build();

        // Should find ClassA but not ClassB
        assertThat(graph.getNodes()).anyMatch(n -> n.getName().equals("ClassA"));
        assertThat(graph.getNodes()).noneMatch(n -> n.getName().equals("ClassB"));
    }

    // ---- Empty project ----

    @Test
    void shouldHandleEmptyProject() {
        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir), Collections.<String>emptyList());
        CodeGraph graph = builder.build();
        assertThat(graph.nodeCount()).isZero();
        assertThat(graph.edgeCount()).isZero();
    }

    // ---- Null project root ----

    @Test
    void shouldHandleNullProjectRoot() {
        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                null, Collections.<String>emptyList());
        CodeGraph graph = builder.build();
        assertThat(graph.nodeCount()).isZero();
    }

    // ---- Parse error handling ----

    @Test
    void shouldHandleParseError() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Broken.java",
                "package com.test;\n\n"
                + "public class Broken {\n"
                + "    this is not valid java\n"
                + "    (((\n"
                + "}\n");
        writeFile(srcDir, "Valid.java",
                "package com.test;\n\n"
                + "public class Valid {\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Broken.java should be skipped, but Valid.java should still be parsed
        assertThat(graph.getNodes()).anyMatch(n -> n.getName().equals("Valid"));
        assertThat(graph.getNodes()).noneMatch(n -> n.getName().equals("Broken"));
    }

    // ---- type() returns "javaparser" ----

    @Test
    void typeReturnsJavaparser() {
        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir), Collections.<String>emptyList());
        assertThat(builder.type()).isEqualTo("javaparser");
    }

    // ---- Interface and enum ----

    @Test
    void shouldParseInterfaceAndEnum() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "MyInterface.java",
                "package com.test;\npublic interface MyInterface {\n    void doSomething();\n}\n");
        writeFile(srcDir, "MyEnum.java",
                "package com.test;\npublic enum MyEnum {\n    A, B, C;\n}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS
                && n.getName().equals("MyInterface"));
        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS
                && n.getName().equals("MyEnum"));
    }

    // ---- DEPENDS_ON from field type ----

    @Test
    void shouldExtractDependsOnFromFieldType() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Container.java",
                "package com.test;\n\n"
                + "import com.test.Helper;\n\n"
                + "public class Container {\n"
                + "    private Helper helper;\n"
                + "}\n");
        writeFile(srcDir, "Helper.java",
                "package com.test;\npublic class Helper {}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // DEPENDS_ON edge from Container to Helper
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getFromId().equals("com.test.Container")
                && e.getToId().equals("com.test.Helper"));
    }

    // ---- DEPENDS_ON from method param ----

    @Test
    void shouldExtractDependsOnFromMethodParam() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Processor.java",
                "package com.test;\n\n"
                + "import com.test.Helper;\n\n"
                + "public class Processor {\n"
                + "    public void run(Helper helper) {\n"
                + "    }\n"
                + "}\n");
        writeFile(srcDir, "Helper.java",
                "package com.test;\npublic class Helper {}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // DEPENDS_ON edge from Processor to Helper (from method param)
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getFromId().equals("com.test.Processor")
                && e.getToId().equals("com.test.Helper"));
    }

    // ---- Scan mode "skills" (keyword-based file filtering) ----

    @Test
    void shouldRespectSkillsScanMode() throws IOException {
        Path pkgA = tempDir.resolve("src/com/test/a");
        Path pkgB = tempDir.resolve("src/com/test/b");
        writeFile(pkgA, "Relevant.java",
                "package com.test.a;\n"
                + "public class Relevant {\n"
                + "    private AllocationPlanService svc;\n"
                + "}\n");
        writeFile(pkgB, "Irrelevant.java",
                "package com.test.b;\npublic class Irrelevant {}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")),
                Collections.<String>emptyList(),
                "skills",
                Collections.singleton("AllocationPlanService"));
        CodeGraph graph = builder.build();

        // Only files containing the keyword are scanned
        assertThat(graph.getNodes()).anyMatch(n -> n.getName().equals("Relevant"));
        assertThat(graph.getNodes()).noneMatch(n -> n.getName().equals("Irrelevant"));
    }

    @Test
    void shouldNotFilterWhenSkillsModeHasNoKeywords() throws IOException {
        Path pkgA = tempDir.resolve("src/com/test/a");
        Path pkgB = tempDir.resolve("src/com/test/b");
        writeFile(pkgA, "ClassA.java", "package com.test.a;\npublic class ClassA {}\n");
        writeFile(pkgB, "ClassB.java", "package com.test.b;\npublic class ClassB {}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")),
                Collections.<String>emptyList(),
                "skills",
                Collections.<String>emptySet());
        CodeGraph graph = builder.build();

        // Empty keywords → no filtering (matches SimpleCodeGraphBuilder contract)
        assertThat(graph.getNodes()).anyMatch(n -> n.getName().equals("ClassA"));
        assertThat(graph.getNodes()).anyMatch(n -> n.getName().equals("ClassB"));
    }

    // ---- Scan mode "packages" (package-prefix file filtering) ----

    @Test
    void shouldRespectPackagesScanMode() throws IOException {
        Path pkgA = tempDir.resolve("src/com/test/a");
        Path pkgB = tempDir.resolve("src/com/test/b");
        writeFile(pkgA, "ClassA.java", "package com.test.a;\npublic class ClassA {}\n");
        writeFile(pkgB, "ClassB.java", "package com.test.b;\npublic class ClassB {}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")),
                Collections.singletonList("com.test.a"),
                "packages",
                Collections.<String>emptySet());
        CodeGraph graph = builder.build();

        assertThat(graph.getNodes()).anyMatch(n -> n.getName().equals("ClassA"));
        assertThat(graph.getNodes()).noneMatch(n -> n.getName().equals("ClassB"));
    }

    // ---- Variable type resolution (no guess-by-capitalization) ----

    @Test
    void shouldResolveCallTargetFromFieldTypeNotNameGuess() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "OrderService.java",
                "package com.test;\n\n"
                + "public class OrderService {\n"
                + "    private AuditLogger logger;\n"
                + "    public void process() {\n"
                + "        logger.record();\n"
                + "    }\n"
                + "}\n");
        writeFile(srcDir, "AuditLogger.java",
                "package com.test;\n\n"
                + "public class AuditLogger {\n"
                + "    public void record() {}\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // The variable is named `logger` (lowercase) but typed `AuditLogger`.
        // Resolution must use the field TYPE, not a capitalized name guess
        // (`Logger#record(*)` would be a false edge).
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.CALLS
                && e.getToId().equals("com.test.AuditLogger#record(*)"));
        assertThat(graph.getEdges()).noneMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.CALLS
                && e.getToId().startsWith("Logger#"));
    }

    @Test
    void shouldResolveCallTargetFromParameterType() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Processor.java",
                "package com.test;\n\n"
                + "public class Processor {\n"
                + "    public void run(Notifier notifier) {\n"
                + "        notifier.notify();\n"
                + "    }\n"
                + "}\n");
        writeFile(srcDir, "Notifier.java",
                "package com.test;\n\n"
                + "public class Notifier {\n"
                + "    public void notify() {}\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.CALLS
                && e.getToId().equals("com.test.Notifier#notify(*)"));
    }

    @Test
    void shouldResolveCallTargetFromLocalVariableType() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Worker.java",
                "package com.test;\n\n"
                + "public class Worker {\n"
                + "    public void doWork() {\n"
                + "        MetricsClient client = new MetricsClient();\n"
                + "        client.report();\n"
                + "    }\n"
                + "}\n");
        writeFile(srcDir, "MetricsClient.java",
                "package com.test;\n\n"
                + "public class MetricsClient {\n"
                + "    public void report() {}\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.CALLS
                && e.getToId().equals("com.test.MetricsClient#report(*)"));
    }

    // ---- Spring dependency-injection edges ----

    @Test
    void shouldExtractAutowiredFieldDependency() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Controller.java",
                "package com.test;\n\n"
                + "public class Controller {\n"
                + "    @Autowired\n"
                + "    private OrderService orderService;\n"
                + "}\n");
        writeFile(srcDir, "OrderService.java",
                "package com.test;\npublic class OrderService {}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getFromId().equals("com.test.Controller")
                && e.getToId().equals("com.test.OrderService"));
    }

    @Test
    void shouldExtractAutowiredConstructorDependency() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "ServiceA.java",
                "package com.test;\n\n"
                + "public class ServiceA {\n"
                + "    @Autowired\n"
                + "    public ServiceA(ServiceB serviceB) {}\n"
                + "}\n");
        writeFile(srcDir, "ServiceB.java",
                "package com.test;\npublic class ServiceB {}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getFromId().equals("com.test.ServiceA")
                && e.getToId().equals("com.test.ServiceB"));
    }

    // ---- Cross-package import resolution ----

    @Test
    void shouldResolveImportedTypes() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Importer.java",
                "package com.test;\n\n"
                + "import org.external.Service;\n\n"
                + "public class Importer {\n"
                + "    private Service service;\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // DEPENDS_ON edge should point to org.external.Service (from import)
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getFromId().equals("com.test.Importer")
                && e.getToId().equals("org.external.Service"));
    }

    // ---- Large-file guard (GAP-5) ----

    @Test
    void shouldSkipFileLargerThanMaxFileBytes() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        // A normal small file that MUST be parsed
        writeFile(srcDir, "Small.java",
                "package com.test;\npublic class Small {\n    public void ok() {}\n}\n");

        // A file that exceeds the tiny max-file-bytes limit (set below)
        StringBuilder big = new StringBuilder("package com.test;\npublic class Huge {\n");
        big.append("    public void big() {\n        // filler\n");
        for (int i = 0; i < 2000; i++) {
            big.append("        System.out.println(\"line ").append(i).append("\");\n");
        }
        big.append("    }\n}\n");
        writeFile(srcDir, "Huge.java", big.toString());

        // maxFileBytes=512 so Huge.java (much larger) is skipped
        CodePathGuard tinyGuard = new CodePathGuard(
                tempDir.resolve("src").toString(),
                Arrays.asList(".java", ".xml"), 500, 512);
        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                tinyGuard, Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS
                && n.getName().equals("Small"));
        assertThat(graph.getNodes()).noneMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS
                && n.getName().equals("Huge"));
    }

    // ---- Key source code excerpt (sourceCode) ----

    @Test
    void shouldSkipTargetAndTestDirectories() throws IOException {
        // A real source file that MUST be parsed
        Path srcDir = tempDir.resolve("src/main/java/com/test");
        writeFile(srcDir, "BizService.java",
                "package com.test;\npublic class BizService {\n    public void run() {}\n}\n");

        // Simulate build artifacts under target/ (as present during `mvn package`)
        Path targetDir = tempDir.resolve("target/generated-sources/com/test");
        writeFile(targetDir, "GeneratedService.java",
                "package com.test;\npublic class GeneratedService {\n    public void gen() {}\n}\n");

        // Simulate test sources under src/test/
        Path testDir = tempDir.resolve("src/test/java/com/test");
        writeFile(testDir, "TestOnlyService.java",
                "package com.test;\npublic class TestOnlyService {\n    public void t() {}\n}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS
                && n.getName().equals("BizService"));
        assertThat(graph.getNodes()).noneMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS
                && n.getName().equals("GeneratedService"));
        assertThat(graph.getNodes()).noneMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS
                && n.getName().equals("TestOnlyService"));
    }

    @Test
    void shouldExtractKeySourceExcerptForClass() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "OrderService.java",
                "package com.test;\n\n"
                + "/**\n"
                + " * 订单服务\n"
                + " */\n"
                + "public class OrderService {\n"
                + "    private OrderRepository orderRepository;\n"
                + "    private String tenantId;\n"
                + "\n"
                + "    public Order getOrder(Long id) {\n"
                + "        return orderRepository.findById(id);\n"
                + "    }\n"
                + "\n"
                + "    public void cancelOrder(Long id) {\n"
                + "        // business logic\n"
                + "    }\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        CodeGraphNode node = graph.getNodes().stream()
                .filter(n -> n.getType() == CodeGraphNode.NodeType.CLASS
                        && n.getName().equals("OrderService"))
                .findFirst().orElse(null);
        assertThat(node).isNotNull();
        assertThat(node.getSourceCode()).isNotNull();
        assertThat(node.getSourceCode()).contains("OrderService");
        assertThat(node.getSourceCode()).contains("getOrder");
        assertThat(node.getSourceCode()).contains("orderRepository");
    }

    @Test
    void shouldStoreFullMethodBodyInSourceCode() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        writeFile(srcDir, "Calc.java",
                "package com.test;\n"
                + "public class Calc {\n"
                + "    public int add(int a, int b) {\n"
                + "        return a + b;\n"
                + "    }\n"
                + "}\n");

        AstCodeGraphBuilder builder = new AstCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        CodeGraphNode node = graph.getNodes().stream()
                .filter(n -> n.getType() == CodeGraphNode.NodeType.CLASS
                        && n.getName().equals("Calc"))
                .findFirst().orElse(null);
        assertThat(node).isNotNull();
        // The full method body (not just the signature) must be preserved
        // so the LLM can diagnose bugs offline.
        assertThat(node.getSourceCode()).contains("add");
        assertThat(node.getSourceCode()).contains("return a + b");
    }
}
