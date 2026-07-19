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

    public CodeGraphNode(String id, NodeType type, String name, String packageName,
                         String className, String returnType, String filePath, int lineNumber) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.packageName = packageName;
        this.className = className;
        this.returnType = returnType;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
    }

    public String getId() { return id; }
    public NodeType getType() { return type; }
    public String getName() { return name; }
    public String getPackageName() { return packageName; }
    public String getClassName() { return className; }
    public String getReturnType() { return returnType; }
    public String getFilePath() { return filePath; }
    public int getLineNumber() { return lineNumber; }

    @Override
    public String toString() {
        return "CodeGraphNode{" + type + " " + id
                + (filePath != null ? " @" + filePath + ":" + lineNumber : "") + "}";
    }
}
