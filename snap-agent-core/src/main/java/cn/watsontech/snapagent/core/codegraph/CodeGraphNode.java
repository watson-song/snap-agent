package cn.watsontech.snapagent.core.codegraph;

/**
 * A node in the code graph representing a class, method, or field.
 *
 * <p>Nodes are identified by a unique {@code id}:
 * <ul>
 *   <li>Class: {@code "com.example.Foo"} (fully qualified name)</li>
 *   <li>Method: {@code "com.example.Foo#bar(String)"} (class + method signature)</li>
 *   <li>Field: {@code "com.example.Foo#fieldName"} (class + field name)</li>
 * </ul>
 */
public class CodeGraphNode {

    public enum NodeType { CLASS, METHOD, FIELD }

    private final String id;
    private final NodeType type;
    private final String name;
    private final String packageName;
    private final String className;
    private final String returnType;
    private final String filePath;
    private final int lineNumber;
    private final String sourceCode;

    /**
     * Backward-compatible constructor: {@code sourceCode} is null.
     */
    public CodeGraphNode(String id, NodeType type, String name, String packageName,
                         String className, String returnType, String filePath, int lineNumber) {
        this(id, type, name, packageName, className, returnType, filePath, lineNumber, null);
    }

    /**
     * Full constructor including a key-source excerpt captured at build time.
     *
     * @param sourceCode optional excerpt of the class/method body (or null). Stored
     *                   with the node so the code can be shown offline — i.e. when
     *                   the runtime JVM has no source files, the CI/integration-built
     *                   graph still carries the key business code for diagnostics.
     */
    public CodeGraphNode(String id, NodeType type, String name, String packageName,
                         String className, String returnType, String filePath, int lineNumber,
                         String sourceCode) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.packageName = packageName;
        this.className = className;
        this.returnType = returnType;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.sourceCode = sourceCode;
    }

    public String getId() { return id; }
    public NodeType getType() { return type; }
    public String getName() { return name; }
    public String getPackageName() { return packageName; }
    public String getClassName() { return className; }
    public String getReturnType() { return returnType; }
    public String getFilePath() { return filePath; }
    public int getLineNumber() { return lineNumber; }
    public String getSourceCode() { return sourceCode; }

    @Override
    public String toString() {
        return "CodeGraphNode{" + type + " " + id
                + (filePath != null ? " @" + filePath + ":" + lineNumber : "") + "}";
    }
}
