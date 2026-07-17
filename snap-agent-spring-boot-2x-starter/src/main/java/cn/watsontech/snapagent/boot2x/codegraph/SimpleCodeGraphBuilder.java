package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lightweight {@link CodeGraphBuilder} using regex-based Java source parsing.
 *
 * <p>No external AST parser dependency (JavaParser/Spoon). Extracts:
 * <ul>
 *   <li>Package declarations</li>
 *   <li>Class/interface/enum declarations (name, extends, implements)</li>
 *   <li>Method declarations (name, return type, parameters)</li>
 *   <li>Field declarations (name, type)</li>
 *   <li>Method calls within method bodies</li>
 * </ul>
 *
 * <p><b>Known limitations (documented honestly):</b></p>
 * <ul>
 *   <li>Comments containing method-call-like patterns produce false positives</li>
 *   <li>Cannot distinguish overloaded methods (same name, different params)</li>
 *   <li>Lambda/Stream method calls may be missed</li>
 *   <li>Generics are preserved in type strings (not erased)</li>
 * </ul>
 *
 * <p>These limitations can be resolved by replacing with a JavaParser-based
 * builder in v0.8.1.</p>
 */
public class SimpleCodeGraphBuilder implements CodeGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(SimpleCodeGraphBuilder.class);

    private final CodePathGuard pathGuard;
    private final List<String> scanPackages;

    // Regex patterns for Java source parsing
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final Pattern CLASS_PATTERN =
            Pattern.compile("(?:public|protected|private)?\\s*(?:abstract\\s+)?(?:final\\s+)?"
                    + "(class|interface|enum)\\s+(\\w+)"
                    + "(?:\\s+extends\\s+([\\w.]+))?"
                    + "(?:\\s+implements\\s+([\\w.,\\s]+))?");

    private static final Pattern METHOD_PATTERN =
            Pattern.compile("(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?"
                    + "(?:abstract\\s+)?(?:synchronized\\s+)?([\\w.<>\\[\\],\\s]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)");

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("(?:private|protected|public)\\s+(?:static\\s+)?(?:final\\s+)?"
                    + "([\\w.<>\\[\\]]+)\\s+(\\w+)\\s*[;=]");

    private static final Pattern METHOD_CALL_PATTERN =
            Pattern.compile("\\b(\\w+)\\.(\\w+)\\s*\\(");

    private static final Set<String> JAVA_KEYWORDS = new HashSet<String>(Arrays.asList(
            "if", "else", "for", "while", "switch", "case", "break", "continue",
            "return", "new", "try", "catch", "finally", "throw", "throws",
            "import", "package", "class", "interface", "enum", "extends",
            "implements", "this", "super", "null", "true", "false"));

    /**
     * Keywords that are NOT valid return types (control-flow statements).
     * Used to filter false-positive method declarations from regex matching.
     * Note: "void" IS a valid return type and must NOT be in this set.
     */
    private static final Set<String> NON_TYPE_KEYWORDS = new HashSet<String>(Arrays.asList(
            "if", "else", "for", "while", "switch", "case", "break", "continue",
            "return", "new", "try", "catch", "finally", "throw", "throws",
            "import", "package", "class", "interface", "enum", "extends",
            "implements", "this", "super"));

    public SimpleCodeGraphBuilder(CodePathGuard pathGuard, List<String> scanPackages) {
        this.pathGuard = pathGuard;
        this.scanPackages = scanPackages != null ? scanPackages : new ArrayList<String>();
    }

    @Override
    public CodeGraph build() {
        if (pathGuard == null || pathGuard.getProjectRoot() == null) {
            log.warn("SimpleCodeGraphBuilder: no project root configured");
            return new CodeGraph(new ArrayList<CodeGraphNode>(), new ArrayList<CodeGraphEdge>());
        }

        Path root = pathGuard.getProjectRoot();
        List<CodeGraphNode> nodes = new ArrayList<CodeGraphNode>();
        List<CodeGraphEdge> edges = new ArrayList<CodeGraphEdge>();

        // Map from className → ClassNode ID for cross-referencing
        Map<String, String> classNameToId = new HashMap<String, String>();

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> javaFiles = new ArrayList<Path>();
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".java"))
                    .forEach(javaFiles::add);

            for (Path javaFile : javaFiles) {
                parseFile(javaFile, root, nodes, edges, classNameToId);
            }
        } catch (IOException e) {
            log.error("Failed to walk project root for code graph: {}", e.getMessage());
        }

        log.info("SimpleCodeGraphBuilder built graph: {} nodes, {} edges (from {} files)",
                nodes.size(), edges.size(), classNameToId.size());
        return new CodeGraph(nodes, edges);
    }

    @Override
    public String type() {
        return "regex";
    }

    private void parseFile(Path javaFile, Path root, List<CodeGraphNode> nodes,
                           List<CodeGraphEdge> edges, Map<String, String> classNameToId) {
        String content;
        try {
            content = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read Java file {}: {}", javaFile, e.getMessage());
            return;
        }

        String relativePath = root.relativize(javaFile).toString().replace('\\', '/');

        // Extract package
        String packageName = "";
        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(content);
        if (pkgMatcher.find()) {
            packageName = pkgMatcher.group(1);
        }

        // Filter by scan-packages
        if (!scanPackages.isEmpty() && !packageName.isEmpty()) {
            boolean matches = false;
            for (String pkg : scanPackages) {
                if (packageName.equals(pkg) || packageName.startsWith(pkg + ".")) {
                    matches = true;
                    break;
                }
            }
            if (!matches) return;
        }

        // Parse classes
        List<ClassInfo> classes = parseClasses(content, packageName, relativePath);
        for (ClassInfo cls : classes) {
            String classId = cls.fqcn;
            nodes.add(new CodeGraphNode(classId, CodeGraphNode.NodeType.CLASS,
                    cls.name, packageName, cls.fqcn, "", relativePath, cls.lineNumber));
            classNameToId.put(cls.name, classId);
            classNameToId.put(cls.fqcn, classId);

            // EXTENDS edge
            if (cls.extendsClass != null && !cls.extendsClass.isEmpty()) {
                String parentId = resolveClassName(cls.extendsClass, packageName);
                edges.add(new CodeGraphEdge(classId, parentId,
                        CodeGraphEdge.EdgeType.EXTENDS, relativePath + ":" + cls.lineNumber));
            }
            // IMPLEMENTS edges
            if (cls.implementsList != null) {
                for (String impl : cls.implementsList) {
                    String implId = resolveClassName(impl.trim(), packageName);
                    edges.add(new CodeGraphEdge(classId, implId,
                            CodeGraphEdge.EdgeType.IMPLEMENTS, relativePath + ":" + cls.lineNumber));
                }
            }
        }

        // Parse methods and fields for each class
        for (int i = 0; i < classes.size(); i++) {
            ClassInfo cls = classes.get(i);
            // Body extends to the start of the next class, or EOF for the last class
            int bodyEnd = content.length();
            if (i + 1 < classes.size()) {
                bodyEnd = classes.get(i + 1).matchStart;
            }
            parseMethodsAndFields(content, cls, bodyEnd, relativePath, packageName,
                    nodes, edges, classNameToId);
        }
    }

    private List<ClassInfo> parseClasses(String content, String packageName, String filePath) {
        List<ClassInfo> classes = new ArrayList<ClassInfo>();
        Matcher m = CLASS_PATTERN.matcher(content);
        while (m.find()) {
            String type = m.group(1); // class, interface, or enum
            String name = m.group(2);
            String extendsClass = m.group(3);
            String implementsStr = m.group(4);

            // Find line number
            int lineNumber = countLines(content.substring(0, m.start())) + 1;

            String fqcn = packageName.isEmpty() ? name : packageName + "." + name;

            List<String> implementsList = null;
            if (implementsStr != null && !implementsStr.trim().isEmpty()) {
                implementsList = Arrays.asList(implementsStr.split(","));
            }

            classes.add(new ClassInfo(name, fqcn, type, extendsClass,
                    implementsList, lineNumber, m.start(), m.end()));
        }
        return classes;
    }

    private void parseMethodsAndFields(String content, ClassInfo cls, int bodyEnd,
                                        String filePath, String packageName,
                                        List<CodeGraphNode> nodes,
                                        List<CodeGraphEdge> edges,
                                        Map<String, String> classNameToId) {
        // Get the class body (from class declaration end to next class or EOF)
        int bodyStart = cls.matchEnd;

        // Find methods within the class body
        Matcher methodMatcher = METHOD_PATTERN.matcher(content);
        methodMatcher.region(bodyStart, bodyEnd);
        while (methodMatcher.find()) {
            String returnType = methodMatcher.group(1).trim();
            String methodName = methodMatcher.group(2);
            String params = methodMatcher.group(3).trim();

            // Skip constructor-like patterns and control-flow keywords (but NOT void)
            if (NON_TYPE_KEYWORDS.contains(returnType) || returnType.isEmpty()) {
                continue;
            }

            int lineNumber = countLines(content.substring(0, methodMatcher.start())) + 1;
            String methodId = cls.fqcn + "#" + methodName + "(" + params + ")";

            nodes.add(new CodeGraphNode(methodId, CodeGraphNode.NodeType.METHOD,
                    methodName, packageName, cls.fqcn, returnType, filePath, lineNumber));

            // DEPENDS_ON edges from params
            if (!params.isEmpty()) {
                for (String param : params.split(",")) {
                    param = param.trim();
                    if (param.isEmpty()) continue;
                    String paramType = extractType(param);
                    if (paramType != null && !isJavaBuiltin(paramType)) {
                        String depId = resolveClassName(paramType, packageName);
                        edges.add(new CodeGraphEdge(cls.fqcn, depId,
                                CodeGraphEdge.EdgeType.DEPENDS_ON,
                                filePath + ":" + lineNumber));
                    }
                }
            }

            // Extract method calls within the method body
            // Find the method body (from end of signature to next method or class end)
            int methodBodyStart = methodMatcher.end();
            int methodBodyEnd = findMethodEnd(content, methodBodyStart);
            String methodBody = content.substring(methodBodyStart, methodBodyEnd);

            Matcher callMatcher = METHOD_CALL_PATTERN.matcher(methodBody);
            while (callMatcher.find()) {
                String objVar = callMatcher.group(1);
                String calledMethod = callMatcher.group(2);

                // Skip keywords and common non-method patterns
                if (JAVA_KEYWORDS.contains(objVar) || "System".equals(objVar)) {
                    continue;
                }

                // Resolve the target method ID (best effort: use variable name as class hint)
                String targetId = resolveMethodId(objVar, calledMethod, cls, classNameToId);
                if (targetId != null) {
                    edges.add(new CodeGraphEdge(methodId, targetId,
                            CodeGraphEdge.EdgeType.CALLS,
                            filePath + ":" + lineNumber));
                }
            }
        }

        // Parse fields
        Matcher fieldMatcher = FIELD_PATTERN.matcher(content);
        fieldMatcher.region(bodyStart, bodyEnd);
        while (fieldMatcher.find()) {
            String fieldType = fieldMatcher.group(1);
            String fieldName = fieldMatcher.group(2);

            int lineNumber = countLines(content.substring(0, fieldMatcher.start())) + 1;
            String fieldId = cls.fqcn + "#" + fieldName;

            nodes.add(new CodeGraphNode(fieldId, CodeGraphNode.NodeType.FIELD,
                    fieldName, packageName, cls.fqcn, fieldType, filePath, lineNumber));

            // DEPENDS_ON edge from class to field type
            if (!isJavaBuiltin(fieldType)) {
                String depId = resolveClassName(fieldType, packageName);
                edges.add(new CodeGraphEdge(cls.fqcn, depId,
                        CodeGraphEdge.EdgeType.DEPENDS_ON,
                        filePath + ":" + lineNumber));
            }
        }
    }

    /**
     * Finds the end of a method body (closing brace of the method).
     * Simple approach: find the next ';' at the same nesting level, or the
     * next method declaration.
     */
    private int findMethodEnd(String content, int start) {
        int braceDepth = 0;
        boolean inMethod = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                braceDepth++;
                inMethod = true;
            } else if (c == '}') {
                braceDepth--;
                if (inMethod && braceDepth == 0) {
                    return i;
                }
            }
        }
        return content.length();
    }

    private String resolveClassName(String type, String packageName) {
        // Strip generics and array brackets
        String cleaned = type.replaceAll("<[^>]+>", "").replaceAll("\\[\\]", "").trim();
        if (cleaned.contains(".")) {
            return cleaned;
        }
        // Simple name → assume same package
        if (packageName.isEmpty()) {
            return cleaned;
        }
        return packageName + "." + cleaned;
    }

    private String resolveMethodId(String varName, String methodName,
                                    ClassInfo currentClass,
                                    Map<String, String> classNameToId) {
        // Best effort: if varName matches a known class name, create a method ID
        String classId = classNameToId.get(varName);
        if (classId != null) {
            // Return a wildcard method ID (params unknown)
            return classId + "#" + methodName + "(*)";
        }
        // If varName starts with uppercase, assume it's a class name
        if (varName.length() > 0 && Character.isUpperCase(varName.charAt(0))) {
            return varName + "#" + methodName + "(*)";
        }
        // If it's "this." → current class method
        if ("this".equals(varName)) {
            return currentClass.fqcn + "#" + methodName + "(*)";
        }
        // Best-effort: capitalize first letter to guess class name (Java convention)
        // e.g. "helper" → "Helper#execute(*)" — may not exist in graph, but edge
        // is still useful for call-chain queries
        if (varName.length() > 0 && !JAVA_KEYWORDS.contains(varName)) {
            String guessedClass = Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
            return guessedClass + "#" + methodName + "(*)";
        }
        return null;
    }

    private String extractType(String paramDecl) {
        // "String name" → "String", "int count" → "int"
        String[] parts = paramDecl.split("\\s+");
        if (parts.length >= 2) {
            return parts[0];
        }
        return null;
    }

    private boolean isJavaBuiltin(String type) {
        if (type == null || type.isEmpty()) return true;
        String cleaned = type.replaceAll("<[^>]+>", "").replaceAll("\\[\\]", "").trim();
        return cleaned.equals("String") || cleaned.equals("int") || cleaned.equals("long")
                || cleaned.equals("double") || cleaned.equals("float") || cleaned.equals("boolean")
                || cleaned.equals("char") || cleaned.equals("byte") || cleaned.equals("short")
                || cleaned.equals("void") || cleaned.equals("Integer") || cleaned.equals("Long")
                || cleaned.equals("Double") || cleaned.equals("Float") || cleaned.equals("Boolean")
                || cleaned.equals("Character") || cleaned.equals("Byte") || cleaned.equals("Short")
                || cleaned.equals("Object") || cleaned.equals("List") || cleaned.equals("Map")
                || cleaned.equals("Set") || cleaned.equals("Collection") || cleaned.equals("Optional")
                || cleaned.startsWith("java.lang.") || cleaned.startsWith("java.util.")
                || cleaned.startsWith("java.io.");
    }

    private int countLines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    private static class ClassInfo {
        final String name;
        final String fqcn;
        final String type; // class, interface, enum
        final String extendsClass;
        final List<String> implementsList;
        final int lineNumber;
        final int matchStart;
        final int matchEnd;

        ClassInfo(String name, String fqcn, String type, String extendsClass,
                  List<String> implementsList, int lineNumber, int matchStart, int matchEnd) {
            this.name = name;
            this.fqcn = fqcn;
            this.type = type;
            this.extendsClass = extendsClass;
            this.implementsList = implementsList;
            this.lineNumber = lineNumber;
            this.matchStart = matchStart;
            this.matchEnd = matchEnd;
        }
    }
}
