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
}
