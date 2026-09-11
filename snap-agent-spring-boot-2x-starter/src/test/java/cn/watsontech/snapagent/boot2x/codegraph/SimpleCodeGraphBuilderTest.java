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

class SimpleCodeGraphBuilderTest {

    @TempDir
    Path tempDir;

    private CodePathGuard makeGuard(Path root) {
        return new CodePathGuard(root.toString(),
                Arrays.asList(".java", ".xml"),
                500, 512 * 1024);
    }

    @Test
    void build_parsesClassAndMethodDeclarations() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("Foo.java"), (
                "package com.test;\n\n"
                + "public class Foo {\n"
                + "    private String name;\n\n"
                + "    public String getName() {\n"
                + "        return name;\n"
                + "    }\n\n"
                + "    public void setName(String name) {\n"
                + "        this.name = name;\n"
                + "    }\n"
                + "}\n").getBytes());

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Should have at least: 1 class node + 2 method nodes + 1 field node
        List<CodeGraphNode> nodes = graph.getNodes();
        assertThat(nodes).isNotEmpty();

        // Check for class node
        assertThat(nodes).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS && n.getName().equals("Foo"));

        // Check for method nodes
        assertThat(nodes).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.METHOD && n.getName().equals("getName"));
        assertThat(nodes).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.METHOD && n.getName().equals("setName"));

        // Check for field node
        assertThat(nodes).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.FIELD && n.getName().equals("name"));
    }

    @Test
    void build_parsesExtendsAndImplements() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("Bar.java"), (
                "package com.test;\n\n"
                + "public class Bar extends Foo implements Runnable {\n"
                + "    public void run() {\n"
                + "        System.out.println(\"running\");\n"
                + "    }\n"
                + "}\n").getBytes());

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Check for EXTENDS edge
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.EXTENDS);

        // Check for IMPLEMENTS edge
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.IMPLEMENTS);
    }

    @Test
    void build_parsesMethodCalls() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("Caller.java"), (
                "package com.test;\n\n"
                + "public class Caller {\n"
                + "    public void doWork() {\n"
                + "        Helper helper = new Helper();\n"
                + "        helper.execute();\n"
                + "    }\n"
                + "}\n").getBytes());

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // Check for CALLS edge from doWork to Helper.execute(*)
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.CALLS
                && e.getToId().contains("execute"));
    }

    @Test
    void build_filtersByScanPackages() throws IOException {
        Path pkgA = tempDir.resolve("src/com/test/a");
        Path pkgB = tempDir.resolve("src/com/test/b");
        Files.createDirectories(pkgA);
        Files.createDirectories(pkgB);
        Files.write(pkgA.resolve("ClassA.java"),
                "package com.test.a;\npublic class ClassA {}\n".getBytes());
        Files.write(pkgB.resolve("ClassB.java"),
                "package com.test.b;\npublic class ClassB {}\n".getBytes());

        // Only scan com.test.a
        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")),
                Collections.singletonList("com.test.a"));
        CodeGraph graph = builder.build();

        // Should find ClassA but not ClassB
        assertThat(graph.getNodes()).anyMatch(n -> n.getName().equals("ClassA"));
        assertThat(graph.getNodes()).noneMatch(n -> n.getName().equals("ClassB"));
    }

    @Test
    void build_emptyProject_returnsEmptyGraph() {
        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir), Collections.<String>emptyList());
        CodeGraph graph = builder.build();
        assertThat(graph.nodeCount()).isZero();
        assertThat(graph.edgeCount()).isZero();
    }

    @Test
    void build_nullProjectRoot_returnsEmptyGraph() {
        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                null, Collections.<String>emptyList());
        CodeGraph graph = builder.build();
        assertThat(graph.nodeCount()).isZero();
    }

    @Test
    void type_returnsRegex() {
        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir), Collections.<String>emptyList());
        assertThat(builder.type()).isEqualTo("regex");
    }

    // ---- GAP-4: DEPENDS_ON edge type from field and method param ----

    @Test
    void build_parsesDependsOnFromFieldType() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("Container.java"), (
                "package com.test;\n\n"
                + "public class Container {\n"
                + "    private Helper helper;\n"
                + "}\n").getBytes());

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // DEPENDS_ON edge from Container to Helper
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getFromId().equals("com.test.Container")
                && e.getToId().equals("com.test.Helper"));
    }

    @Test
    void build_parsesDependsOnFromMethodParam() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("Processor.java"), (
                "package com.test;\n\n"
                + "public class Processor {\n"
                + "    public void process(Helper helper) {\n"
                + "    }\n"
                + "}\n").getBytes());

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // DEPENDS_ON edge from Processor to Helper (from method param)
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getFromId().equals("com.test.Processor")
                && e.getToId().equals("com.test.Helper"));
    }

    @Test
    void build_parsesInterfaceAndEnum() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("MyInterface.java"),
                "package com.test;\npublic interface MyInterface {\n    void doSomething();\n}\n".getBytes());
        Files.write(srcDir.resolve("MyEnum.java"),
                "package com.test;\npublic enum MyEnum {\n    A, B, C;\n}\n".getBytes());

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS && n.getName().equals("MyInterface"));
        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS && n.getName().equals("MyEnum"));
    }

    // ---- Fix 1 (P1-5): import statements for cross-package type resolution ----

    @Test
    void shouldResolveImportedTypes() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        // A class that imports java.util.List and uses it as a field type.
        // Note: List is a java.util builtin so isJavaBuiltin skips it — use a
        // non-builtin imported type instead.
        Files.write(srcDir.resolve("Importer.java"), (
                "package com.test;\n\n"
                + "import org.external.Service;\n\n"
                + "public class Importer {\n"
                + "    private Service service;\n"
                + "}\n").getBytes());

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // DEPENDS_ON edge should point to org.external.Service (from import),
        // NOT com.test.Service (same-package fallback).
        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getFromId().equals("com.test.Importer")
                && e.getToId().equals("org.external.Service"));
        assertThat(graph.getEdges()).noneMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getToId().equals("com.test.Service"));
    }

    @Test
    void shouldResolveCustomImportedTypes() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        // A class in com.test that imports a type from com.other and uses it as
        // a method parameter. The DEPENDS_ON edge must point to com.other.Helper.
        Files.write(srcDir.resolve("Worker.java"), (
                "package com.test;\n\n"
                + "import com.other.Helper;\n\n"
                + "public class Worker {\n"
                + "    public void run(Helper helper) {\n"
                + "    }\n"
                + "}\n").getBytes());

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getEdges()).anyMatch(e ->
                e.getType() == CodeGraphEdge.EdgeType.DEPENDS_ON
                && e.getFromId().equals("com.test.Worker")
                && e.getToId().equals("com.other.Helper"));
    }

    // ---- Fix 2 (P2-11): parallel build ----

    @Test
    void shouldBuildInParallel() throws IOException {
        Path srcDir = tempDir.resolve("src/com/parallel");
        Files.createDirectories(srcDir);
        // Create 50+ temp .java files so parallelStream() uses the common pool.
        for (int i = 0; i < 60; i++) {
            Files.write(srcDir.resolve("Class" + i + ".java"), (
                    "package com.parallel;\n\n"
                    + "public class Class" + i + " {\n"
                    + "    public void method" + i + "() {\n"
                    + "    }\n"
                    + "}\n").getBytes());
        }

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(tempDir.resolve("src")), Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        // 60 classes + 60 methods = 120 nodes
        assertThat(graph.nodeCount()).isEqualTo(120);
        // No edges expected (no calls, no dependencies, no extends)
        assertThat(graph.edgeCount()).isZero();
    }

    // ---- Large-file guard (GAP-5) ----

    @Test
    void shouldSkipFileLargerThanMaxFileBytes() throws IOException {
        Path srcDir = tempDir.resolve("src/com/test");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("Small.java"), (
                "package com.test;\npublic class Small {\n    public void ok() {}\n}\n").getBytes());

        StringBuilder big = new StringBuilder("package com.test;\npublic class Huge {\n");
        big.append("    public void big() {\n");
        for (int i = 0; i < 2000; i++) {
            big.append("        int x").append(i).append(" = ").append(i).append(";\n");
        }
        big.append("    }\n}\n");
        Files.write(srcDir.resolve("Huge.java"), big.toString().getBytes());

        CodePathGuard tinyGuard = new CodePathGuard(
                tempDir.resolve("src").toString(),
                Arrays.asList(".java", ".xml"), 500, 512);
        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                tinyGuard, Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS && n.getName().equals("Small"));
        assertThat(graph.getNodes()).noneMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS && n.getName().equals("Huge"));
    }

    @Test
    void shouldSkipTargetAndTestDirectories() throws IOException {
        Path srcDir = tempDir.resolve("src/main/java/com/test");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("BizService.java"), (
                "package com.test;\npublic class BizService {\n    public void run() {}\n}\n").getBytes());

        Path targetDir = tempDir.resolve("target/generated-sources/com/test");
        Files.createDirectories(targetDir);
        Files.write(targetDir.resolve("GeneratedService.java"), (
                "package com.test;\npublic class GeneratedService {\n    public void gen() {}\n}\n").getBytes());

        Path testDir = tempDir.resolve("src/test/java/com/test");
        Files.createDirectories(testDir);
        Files.write(testDir.resolve("TestOnlyService.java"), (
                "package com.test;\npublic class TestOnlyService {\n    public void t() {}\n}\n").getBytes());

        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                new CodePathGuard(tempDir.toString(), Arrays.asList(".java", ".xml"), 500, 512 * 1024),
                Collections.<String>emptyList());
        CodeGraph graph = builder.build();

        assertThat(graph.getNodes()).anyMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS && n.getName().equals("BizService"));
        assertThat(graph.getNodes()).noneMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS && n.getName().equals("GeneratedService"));
        assertThat(graph.getNodes()).noneMatch(n ->
                n.getType() == CodeGraphNode.NodeType.CLASS && n.getName().equals("TestOnlyService"));
    }
}
