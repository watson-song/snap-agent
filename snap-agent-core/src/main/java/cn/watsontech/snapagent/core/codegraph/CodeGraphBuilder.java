package cn.watsontech.snapagent.core.codegraph;

/**
 * Code graph builder SPI.
 *
 * <p>Implementations parse source code and produce a {@link CodeGraph}.
 * The default starter implementation ({@code SimpleCodeGraphBuilder}) uses
 * regex-based parsing. Custom implementations can use JavaParser/Spoon for
 * more accurate AST-level analysis.</p>
 */
public interface CodeGraphBuilder {

    /**
     * Build a code graph from the project source code.
     *
     * @return the constructed code graph (may be empty, never null)
     */
    CodeGraph build();

    /**
     * Source type identifier (e.g. "regex", "javaparser").
     *
     * @return type string used for logging and diagnostics
     */
    String type();
}
