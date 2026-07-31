package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * AST-based {@link CodeGraphBuilder} powered by JavaParser.
 *
 * <p>Replaces the regex-based {@link SimpleCodeGraphBuilder} with a proper AST
 * parser, eliminating false positives from comments and correctly handling
 * overloaded methods, generics erasure, lambda/stream calls, and inner/anonymous
 * classes.</p>
 *
 * <p>This implementation parses each {@code .java} file into a
 * {@link CompilationUnit} using {@link JavaParser}, then walks the AST with
 * {@link VoidVisitorAdapter} implementations to extract nodes and edges.</p>
 *
 * <h3>Extracted elements</h3>
 * <ul>
 *   <li>Class/interface/enum declarations → CLASS nodes</li>
 *   <li>Method declarations → METHOD nodes (with resolved return types and param types)</li>
 *   <li>Field declarations → FIELD nodes</li>
 *   <li>Method calls within method bodies → CALLS edges</li>
 *   <li>Extends → EXTENDS edges</li>
 *   <li>Implements → IMPLEMENTS edges</li>
 *   <li>Field type dependencies → DEPENDS_ON edges</li>
 *   <li>Method parameter type dependencies → DEPENDS_ON edges</li>
 * </ul>
 *
 * <h3>Method-call resolution</h3>
 * <p>Without a configured {@code SymbolSolver} (which requires a compiled
 * classpath), method calls are resolved using a best-effort heuristic:
 * the scope expression of the call is inspected. If the scope is a
 * {@code NameExpr} whose name matches a known class, the call target is
 * {@code ClassName#methodName(*)}. If the scope is a variable, the first-letter
 * capitalization convention is used to guess the class name. Calls on
 * {@code this.} resolve to the enclosing class.</p>
 *
 * <p>All code is Java 8 compatible.</p>
 */
public class AstCodeGraphBuilder implements CodeGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(AstCodeGraphBuilder.class);

    private final CodePathGuard pathGuard;
    private final List<String> scanPackages;

    private static final Set<String> JAVA_KEYWORDS = new HashSet<String>(Arrays.asList(
            "if", "else", "for", "while", "switch", "case", "break", "continue",
            "return", "new", "try", "catch", "finally", "throw", "throws",
            "import", "package", "class", "interface", "enum", "extends",
            "implements", "this", "super", "null", "true", "false"));

    public AstCodeGraphBuilder(CodePathGuard pathGuard, List<String> scanPackages) {
        this.pathGuard = pathGuard;
        this.scanPackages = scanPackages != null ? scanPackages : new ArrayList<String>();
    }

    @Override
    public CodeGraph build() {
        if (pathGuard == null || pathGuard.getProjectRoot() == null) {
            log.warn("AstCodeGraphBuilder: no project root configured");
            return new CodeGraph(new ArrayList<CodeGraphNode>(), new ArrayList<CodeGraphEdge>());
        }

        Path root = pathGuard.getProjectRoot();

        List<Path> javaFiles = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".java"))
                    .forEach(javaFiles::add);
        } catch (IOException e) {
            log.error("Failed to walk project root for code graph: {}", e.getMessage());
            return new CodeGraph(new ArrayList<CodeGraphNode>(), new ArrayList<CodeGraphEdge>());
        }

        // Parse files in parallel — each thread accumulates into its own ParseResult,
        // then results are merged on the calling thread to avoid shared-state races.
        final List<ParseResult> results = new ArrayList<ParseResult>();
        final Path rootFinal = root;
        javaFiles.parallelStream().forEach(javaFile -> {
            ParseResult result = parseFile(javaFile, rootFinal);
            if (result != null) {
                synchronized (results) {
                    results.add(result);
                }
            }
        });

        // Merge per-file results into a single graph.
        // Build a combined classNameToId map first so that CALLS edge resolution
        // can find target classes across files.
        List<CodeGraphNode> nodes = new ArrayList<CodeGraphNode>();
        List<CodeGraphEdge> edges = new ArrayList<CodeGraphEdge>();
        Map<String, String> classNameToId = new HashMap<String, String>();
        for (ParseResult r : results) {
            nodes.addAll(r.nodes);
            edges.addAll(r.edges);
            classNameToId.putAll(r.classNameToId);
        }

        // Second pass: re-resolve CALLS edges now that all class names are known.
        // The per-file parse already produced best-effort target IDs; we just
        // upgrade any simple-name class references to FQCNs where possible.
        List<CodeGraphEdge> resolvedEdges = new ArrayList<CodeGraphEdge>(edges.size());
        for (CodeGraphEdge edge : edges) {
            if (edge.getType() == CodeGraphEdge.EdgeType.CALLS) {
                String upgradedToId = upgradeTargetId(edge.getToId(), classNameToId);
                resolvedEdges.add(new CodeGraphEdge(edge.getFromId(), upgradedToId,
                        edge.getType(), edge.getContext()));
            } else {
                resolvedEdges.add(edge);
            }
        }

        log.info("AstCodeGraphBuilder built graph: {} nodes, {} edges (from {} files)",
                nodes.size(), resolvedEdges.size(), results.size());
        return new CodeGraph(nodes, resolvedEdges);
    }

    @Override
    public String type() {
        return "javaparser";
    }

    // ---- Per-file parsing ----

    private ParseResult parseFile(Path javaFile, Path root) {
        String content;
        try {
            content = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read Java file {}: {}", javaFile, e.getMessage());
            return null;
        }

        String relativePath = root.relativize(javaFile).toString().replace('\\', '/');

        com.github.javaparser.ParseResult<CompilationUnit> parseResult;
        try {
            // Create a new JavaParser per call — JavaParser.parse() is NOT thread-safe
            JavaParser parser = new JavaParser();
            parseResult = parser.parse(content);
        } catch (RuntimeException e) {
            log.warn("Parse error in {}: {}", javaFile, e.getMessage());
            return null;
        }
        if (!parseResult.isSuccessful() || !parseResult.getResult().isPresent()) {
            log.warn("Parse failed for {}: {}", javaFile,
                    parseResult.getProblems().isEmpty() ? "unknown" : parseResult.getProblems().get(0));
            return null;
        }

        CompilationUnit cu = parseResult.getResult().get();
        String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

        // Filter by scan-packages
        if (!scanPackages.isEmpty() && !packageName.isEmpty()) {
            boolean matches = false;
            for (String pkg : scanPackages) {
                if (packageName.equals(pkg) || packageName.startsWith(pkg + ".")) {
                    matches = true;
                    break;
                }
            }
            if (!matches) return null;
        }

        // Build an import map for type resolution (simple name → FQN)
        Map<String, String> imports = new HashMap<String, String>();
        for (com.github.javaparser.ast.ImportDeclaration imp : cu.getImports()) {
            String fqn = imp.getNameAsString();
            int lastDot = fqn.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
            imports.put(simpleName, fqn);
        }

        ParseResult result = new ParseResult();
        FileContext ctx = new FileContext(relativePath, packageName, imports,
                result.nodes, result.edges, result.classNameToId);

        cu.accept(new GraphBuildingVisitor(), ctx);

        return result;
    }

    // ---- AST Visitor ----

    /**
     * Visitor that walks the AST and builds graph nodes and edges.
     * Visits class/interface/enum declarations, method declarations,
     * field declarations, and method calls.
     */
    private static class GraphBuildingVisitor extends VoidVisitorAdapter<FileContext> {

        @Override
        public void visit(ClassOrInterfaceDeclaration decl, FileContext ctx) {
            // Build FQCN: outer package + nested name chain
            String fqcn = buildFqcn(decl, ctx.packageName);
            String name = decl.getNameAsString();
            int lineNumber = decl.getBegin().map(b -> b.line).orElse(0);

            ctx.nodes.add(new CodeGraphNode(fqcn, CodeGraphNode.NodeType.CLASS,
                    name, ctx.packageName, fqcn, "", ctx.filePath, lineNumber));
            ctx.classNameToId.put(name, fqcn);
            ctx.classNameToId.put(fqcn, fqcn);

            // EXTENDS edge
            for (ClassOrInterfaceType superType : decl.getExtendedTypes()) {
                String parentId = resolveTypeName(superType, ctx);
                ctx.edges.add(new CodeGraphEdge(fqcn, parentId,
                        CodeGraphEdge.EdgeType.EXTENDS, ctx.filePath + ":" + lineNumber));
            }

            // IMPLEMENTS edges
            for (ClassOrInterfaceType implType : decl.getImplementedTypes()) {
                String implId = resolveTypeName(implType, ctx);
                ctx.edges.add(new CodeGraphEdge(fqcn, implId,
                        CodeGraphEdge.EdgeType.IMPLEMENTS, ctx.filePath + ":" + lineNumber));
            }

            // Save the current class context for nested method/field visitors
            ClassContext prevClass = ctx.currentClass;
            ctx.currentClass = new ClassContext(fqcn, name);
            super.visit(decl, ctx);
            ctx.currentClass = prevClass;
        }

        @Override
        public void visit(EnumDeclaration decl, FileContext ctx) {
            String fqcn = buildFqcn(decl, ctx.packageName);
            String name = decl.getNameAsString();
            int lineNumber = decl.getBegin().map(b -> b.line).orElse(0);

            ctx.nodes.add(new CodeGraphNode(fqcn, CodeGraphNode.NodeType.CLASS,
                    name, ctx.packageName, fqcn, "", ctx.filePath, lineNumber));
            ctx.classNameToId.put(name, fqcn);
            ctx.classNameToId.put(fqcn, fqcn);

            // IMPLEMENTS edges for enums
            for (ClassOrInterfaceType implType : decl.getImplementedTypes()) {
                String implId = resolveTypeName(implType, ctx);
                ctx.edges.add(new CodeGraphEdge(fqcn, implId,
                        CodeGraphEdge.EdgeType.IMPLEMENTS, ctx.filePath + ":" + lineNumber));
            }

            ClassContext prevClass = ctx.currentClass;
            ctx.currentClass = new ClassContext(fqcn, name);
            super.visit(decl, ctx);
            ctx.currentClass = prevClass;
        }

        @Override
        public void visit(MethodDeclaration decl, FileContext ctx) {
            if (ctx.currentClass == null) {
                super.visit(decl, ctx);
                return;
            }

            String methodName = decl.getNameAsString();
            String returnType = decl.getType() != null ? eraseGenerics(decl.getType().asString()) : "void";
            int lineNumber = decl.getBegin().map(b -> b.line).orElse(0);

            // Build parameter type list for the method signature
            StringBuilder paramSig = new StringBuilder();
            String sep = "";
            for (Parameter param : decl.getParameters()) {
                String paramType = eraseGenerics(param.getType().asString());
                paramSig.append(sep).append(paramType);
                sep = ", ";

                // DEPENDS_ON edge from class to param type
                if (!isJavaBuiltin(paramType)) {
                    String depId = resolveTypeName(paramType, ctx);
                    ctx.edges.add(new CodeGraphEdge(ctx.currentClass.fqcn, depId,
                            CodeGraphEdge.EdgeType.DEPENDS_ON, ctx.filePath + ":" + lineNumber));
                }
            }

            String methodId = ctx.currentClass.fqcn + "#" + methodName + "(" + paramSig + ")";
            ctx.nodes.add(new CodeGraphNode(methodId, CodeGraphNode.NodeType.METHOD,
                    methodName, ctx.packageName, ctx.currentClass.fqcn,
                    returnType, ctx.filePath, lineNumber));

            // Visit the method body to find CALLS edges
            // Track the current method context
            String prevMethodId = ctx.currentMethodId;
            ctx.currentMethodId = methodId;
            super.visit(decl, ctx);
            ctx.currentMethodId = prevMethodId;
        }

        @Override
        public void visit(ConstructorDeclaration decl, FileContext ctx) {
            if (ctx.currentClass == null) {
                super.visit(decl, ctx);
                return;
            }

            // Constructors are treated as methods named after the class
            String methodName = decl.getNameAsString();
            int lineNumber = decl.getBegin().map(b -> b.line).orElse(0);

            StringBuilder paramSig = new StringBuilder();
            String sep = "";
            for (Parameter param : decl.getParameters()) {
                String paramType = eraseGenerics(param.getType().asString());
                paramSig.append(sep).append(paramType);
                sep = ", ";

                if (!isJavaBuiltin(paramType)) {
                    String depId = resolveTypeName(paramType, ctx);
                    ctx.edges.add(new CodeGraphEdge(ctx.currentClass.fqcn, depId,
                            CodeGraphEdge.EdgeType.DEPENDS_ON, ctx.filePath + ":" + lineNumber));
                }
            }

            String methodId = ctx.currentClass.fqcn + "#" + methodName + "(" + paramSig + ")";
            ctx.nodes.add(new CodeGraphNode(methodId, CodeGraphNode.NodeType.METHOD,
                    methodName, ctx.packageName, ctx.currentClass.fqcn,
                    ctx.currentClass.fqcn, ctx.filePath, lineNumber));

            String prevMethodId = ctx.currentMethodId;
            ctx.currentMethodId = methodId;
            super.visit(decl, ctx);
            ctx.currentMethodId = prevMethodId;
        }

        @Override
        public void visit(FieldDeclaration decl, FileContext ctx) {
            if (ctx.currentClass == null) {
                super.visit(decl, ctx);
                return;
            }

            for (VariableDeclarator var : decl.getVariables()) {
                String fieldName = var.getNameAsString();
                String fieldType = eraseGenerics(var.getType().asString());
                int lineNumber = var.getBegin().map(b -> b.line).orElse(0);

                String fieldId = ctx.currentClass.fqcn + "#" + fieldName;
                ctx.nodes.add(new CodeGraphNode(fieldId, CodeGraphNode.NodeType.FIELD,
                        fieldName, ctx.packageName, ctx.currentClass.fqcn,
                        fieldType, ctx.filePath, lineNumber));

                // DEPENDS_ON edge from class to field type
                if (!isJavaBuiltin(fieldType)) {
                    String depId = resolveTypeName(fieldType, ctx);
                    ctx.edges.add(new CodeGraphEdge(ctx.currentClass.fqcn, depId,
                            CodeGraphEdge.EdgeType.DEPENDS_ON, ctx.filePath + ":" + lineNumber));
                }
            }

            // Do NOT call super.visit — we don't need to visit the initializer
            // expressions (they could contain method calls but that adds noise).
            // If we want calls from field initializers, uncomment:
            // super.visit(decl, ctx);
        }

        @Override
        public void visit(MethodCallExpr call, FileContext ctx) {
            // Only create CALLS edges if we're inside a method
            if (ctx.currentMethodId != null) {
                String calledMethodName = call.getNameAsString();
                String targetId = resolveCallTarget(call, calledMethodName, ctx);
                if (targetId != null) {
                    int lineNumber = call.getBegin().map(b -> b.line).orElse(0);
                    ctx.edges.add(new CodeGraphEdge(ctx.currentMethodId, targetId,
                            CodeGraphEdge.EdgeType.CALLS, ctx.filePath + ":" + lineNumber));
                }
            }

            // Continue visiting children (handles chained calls, lambda bodies, etc.)
            super.visit(call, ctx);
        }
    }

    // ---- Call target resolution ----

    /**
     * Best-effort resolution of a method call's target method node ID.
     * Uses the scope expression to determine the target class.
     */
    private static String resolveCallTarget(MethodCallExpr call, String calledMethodName,
                                           FileContext ctx) {
        Optional<Expression> scopeOpt = call.getScope();

        if (!scopeOpt.isPresent()) {
            // No scope: unqualified call → this class method
            if (ctx.currentClass != null) {
                return ctx.currentClass.fqcn + "#" + calledMethodName + "(*)";
            }
            return null;
        }

        Expression scope = scopeOpt.get();

        // scope is a NameExpr (e.g. "helper.execute()")
        if (scope instanceof NameExpr) {
            String varName = ((NameExpr) scope).getNameAsString();

            // Skip keywords
            if (JAVA_KEYWORDS.contains(varName)) {
                return null;
            }

            // "this" → current class
            if ("this".equals(varName)) {
                if (ctx.currentClass != null) {
                    return ctx.currentClass.fqcn + "#" + calledMethodName + "(*)";
                }
                return null;
            }

            // "super" → parent class (best effort: just use Super#method)
            if ("super".equals(varName)) {
                return "super#" + calledMethodName + "(*)";
            }

            // Check if varName is a known class name (static call)
            String classId = ctx.classNameToId.get(varName);
            if (classId != null) {
                return classId + "#" + calledMethodName + "(*)";
            }

            // If varName starts with uppercase, assume it's a class name
            if (!varName.isEmpty() && Character.isUpperCase(varName.charAt(0))) {
                return varName + "#" + calledMethodName + "(*)";
            }

            // Best-effort: capitalize first letter (Java convention)
            // e.g. "helper" → "Helper#execute(*)"
            if (!varName.isEmpty()) {
                String guessedClass = Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
                return guessedClass + "#" + calledMethodName + "(*)";
            }

            return null;
        }

        // scope is a MethodCallExpr (chained call, e.g. stream().filter(...))
        // We still record the call but with a generic target
        if (scope instanceof MethodCallExpr) {
            // For chained calls (stream API, builder pattern), the target class
            // is unknown without symbol resolution. Record with scope as hint.
            return calledMethodName + "(*)";
        }

        // scope is a FieldAccessExpr (e.g. this.field.method() or Class.field.method())
        if (scope instanceof FieldAccessExpr) {
            FieldAccessExpr fae = (FieldAccessExpr) scope;
            String fieldName = fae.getNameAsString();
            // Check if the field's chain root is a known class
            String classId = ctx.classNameToId.get(fieldName);
            if (classId != null) {
                return classId + "#" + calledMethodName + "(*)";
            }
            if (!fieldName.isEmpty() && Character.isUpperCase(fieldName.charAt(0))) {
                return fieldName + "#" + calledMethodName + "(*)";
            }
        }

        // For other scope types (object creation, lambda, etc.), record generically
        return calledMethodName + "(*)";
    }

    /**
     * After merging all per-file results, upgrade simple-name target IDs
     * in CALLS edges to FQCNs where possible.
     */
    private static String upgradeTargetId(String toId, Map<String, String> classNameToId) {
        if (toId == null || !toId.contains("#")) {
            return toId;
        }
        int hashIdx = toId.indexOf('#');
        String className = toId.substring(0, hashIdx);
        String methodPart = toId.substring(hashIdx);

        // If className is already an FQCN (contains a dot), keep as-is
        if (className.contains(".")) {
            return toId;
        }

        // Try to resolve simple name to FQCN
        String fqcn = classNameToId.get(className);
        if (fqcn != null) {
            return fqcn + methodPart;
        }
        return toId;
    }

    // ---- Type resolution helpers ----

    /**
     * Build a fully qualified class name from a class declaration and its package.
     * Handles nested classes by walking up the parent node chain.
     */
    private static String buildFqcn(ClassOrInterfaceDeclaration decl, String packageName) {
        StringBuilder name = new StringBuilder(decl.getNameAsString());
        com.github.javaparser.ast.Node parent = decl.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof ClassOrInterfaceDeclaration) {
                name.insert(0, ((ClassOrInterfaceDeclaration) parent).getNameAsString() + ".");
            } else if (parent instanceof EnumDeclaration) {
                name.insert(0, ((EnumDeclaration) parent).getNameAsString() + ".");
            }
            parent = parent.getParentNode().orElse(null);
        }
        return packageName.isEmpty() ? name.toString() : packageName + "." + name.toString();
    }

    private static String buildFqcn(EnumDeclaration decl, String packageName) {
        StringBuilder name = new StringBuilder(decl.getNameAsString());
        com.github.javaparser.ast.Node parent = decl.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof ClassOrInterfaceDeclaration) {
                name.insert(0, ((ClassOrInterfaceDeclaration) parent).getNameAsString() + ".");
            } else if (parent instanceof EnumDeclaration) {
                name.insert(0, ((EnumDeclaration) parent).getNameAsString() + ".");
            }
            parent = parent.getParentNode().orElse(null);
        }
        return packageName.isEmpty() ? name.toString() : packageName + "." + name.toString();
    }

    /**
     * Resolve a type name to an FQCN using imports and package fallback.
     */
    private static String resolveTypeName(ClassOrInterfaceType type, FileContext ctx) {
        String raw = type.getNameAsString();
        return resolveTypeName(raw, ctx);
    }

    private static String resolveTypeName(String rawType, FileContext ctx) {
        // Erase generics and array brackets
        String cleaned = eraseGenerics(rawType).replaceAll("\\[\\]", "").trim();

        // Already fully qualified
        if (cleaned.contains(".")) {
            return cleaned;
        }

        // Check imports
        String resolved = ctx.imports.get(cleaned);
        if (resolved != null) {
            return resolved;
        }

        // java.lang auto-import
        if (isJavaLangType(cleaned)) {
            return "java.lang." + cleaned;
        }

        // Fallback: same package
        if (ctx.packageName.isEmpty()) {
            return cleaned;
        }
        return ctx.packageName + "." + cleaned;
    }

    /**
     * Erase generics from a type string: {@code List<String>} → {@code List}.
     * Handles nested generics: {@code Map<String, List<Integer>>} → {@code Map}.
     */
    private static String eraseGenerics(String type) {
        if (type == null || type.isEmpty()) {
            return type;
        }
        int idx = type.indexOf('<');
        if (idx < 0) {
            return type;
        }
        return type.substring(0, idx).trim();
    }

    // ---- Java built-in type detection ----

    private static final Set<String> JAVA_LANG_TYPES = new HashSet<String>(Arrays.asList(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Character",
            "Byte", "Short", "Object", "Number", "Class", "Math", "System",
            "Exception", "RuntimeException", "Throwable", "Error",
            "StringBuilder", "StringBuffer", "Thread", "Runnable", "Iterable",
            "Comparable", "Override", "Deprecated", "SuppressWarnings"));

    private static boolean isJavaLangType(String typeName) {
        return JAVA_LANG_TYPES.contains(typeName);
    }

    private static boolean isJavaBuiltin(String type) {
        if (type == null || type.isEmpty()) return true;
        String cleaned = eraseGenerics(type).replaceAll("\\[\\]", "").trim();

        // Primitives
        if (cleaned.equals("int") || cleaned.equals("long") || cleaned.equals("double")
                || cleaned.equals("float") || cleaned.equals("boolean")
                || cleaned.equals("char") || cleaned.equals("byte") || cleaned.equals("short")
                || cleaned.equals("void")) {
            return true;
        }

        // java.lang wrappers and common types
        if (isJavaLangType(cleaned)) {
            return true;
        }

        // java.util collection types
        if (cleaned.equals("List") || cleaned.equals("Map") || cleaned.equals("Set")
                || cleaned.equals("Collection") || cleaned.equals("Optional")
                || cleaned.equals("ArrayList") || cleaned.equals("HashMap")
                || cleaned.equals("HashSet") || cleaned.equals("LinkedList")) {
            return true;
        }

        // Fully-qualified java.* types
        return cleaned.startsWith("java.lang.") || cleaned.startsWith("java.util.")
                || cleaned.startsWith("java.io.") || cleaned.startsWith("java.time.");
    }

    // ---- Context classes ----

    /**
     * Per-file context passed to the AST visitor. Accumulates nodes and edges
     * for a single compilation unit.
     */
    private static class FileContext {
        final String filePath;
        final String packageName;
        final Map<String, String> imports;
        final List<CodeGraphNode> nodes;
        final List<CodeGraphEdge> edges;
        final Map<String, String> classNameToId;
        ClassContext currentClass;
        String currentMethodId;

        FileContext(String filePath, String packageName, Map<String, String> imports,
                    List<CodeGraphNode> nodes, List<CodeGraphEdge> edges,
                    Map<String, String> classNameToId) {
            this.filePath = filePath;
            this.packageName = packageName;
            this.imports = imports;
            this.nodes = nodes;
            this.edges = edges;
            this.classNameToId = classNameToId;
        }
    }

    private static class ClassContext {
        final String fqcn;
        final String name;
        ClassContext(String fqcn, String name) {
            this.fqcn = fqcn;
            this.name = name;
        }
    }

    /**
     * Per-file accumulation buffer used by the parallel build phase.
     */
    private static class ParseResult {
        final List<CodeGraphNode> nodes = new ArrayList<CodeGraphNode>();
        final List<CodeGraphEdge> edges = new ArrayList<CodeGraphEdge>();
        final Map<String, String> classNameToId = new HashMap<String, String>();
    }
}
