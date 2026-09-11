package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Standalone CLI for pre-building a code graph into an H2 file database.
 *
 * <p>Intended for the integration step (not necessarily CI): when SnapAgent is
 * integrated into a host application, the source tree is available. This CLI is
 * invoked by the integration-tool skill to produce a {@code codegraph.mv.db}
 * file, which the embedded runtime then loads WITHOUT source files present.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -cp snap-agent-spring-boot-2x-starter.jar:snap-agent-core.jar:h2.jar \
 *     cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli \
 *     --project-root /path/to/project \
 *     --scan-mode skills \
 *     --skill-dir /path/to/docs/skills \
 *     --output ./data/codegraph
 * </pre>
 *
 * <p>Then configure {@code snap-agent.code-graph.persistence=h2} and
 * {@code snap-agent.code-graph.h2-url=jdbc:h2:file:./data/codegraph}. The
 * application loads the pre-built graph (including key-source full method
 * bodies) from disk without scanning.</p>
 */
public class CodeGraphCli {

    public static void main(String[] args) throws Exception {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Parse arguments and build the graph. Returns a process exit code
     * (0 = success, non-zero = failure) so it is testable without
     * {@code System.exit}.
     */
    public static int run(String[] args) throws Exception {
        String projectRoot = null;
        String outputUrl = "jdbc:h2:file:./data/codegraph";
        List<String> scanPackages = new ArrayList<String>();
        String scanMode = "all";
        Set<String> skillKeywords = new HashSet<String>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--project-root":
                    projectRoot = requireValue(args, ++i, arg);
                    break;
                case "--scan-packages":
                    String pkgs = requireValue(args, ++i, arg);
                    for (String pkg : pkgs.split(",")) {
                        String trimmed = pkg.trim();
                        if (!trimmed.isEmpty()) {
                            scanPackages.add(trimmed);
                        }
                    }
                    break;
                case "--scan-mode":
                    scanMode = requireValue(args, ++i, arg);
                    break;
                case "--skill-dir":
                    String skillDir = requireValue(args, ++i, arg);
                    skillKeywords = SkillKeywordExtractor.extractFromDirectory(Paths.get(skillDir));
                    break;
                case "--output":
                    String output = requireValue(args, ++i, arg);
                    outputUrl = output.startsWith("jdbc:h2:") ? output : "jdbc:h2:file:" + output;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return 0;
                default:
                    System.err.println("Unknown argument: " + arg);
                    printUsage();
                    return 1;
            }
        }

        if (projectRoot == null) {
            System.err.println("Error: --project-root is required");
            printUsage();
            return 1;
        }

        Path rootPath = Paths.get(projectRoot).toAbsolutePath().normalize();
        if (!java.nio.file.Files.exists(rootPath)) {
            System.err.println("Error: project root does not exist: " + rootPath);
            return 1;
        }

        System.out.println("Building code graph...");
        System.out.println("  Project root: " + rootPath);
        System.out.println("  Scan mode: " + scanMode
                + (scanMode.equalsIgnoreCase("skills") ? " (" + skillKeywords.size() + " keywords)" : ""));
        System.out.println("  Scan packages: " + (scanPackages.isEmpty() ? "(all)" : scanPackages));
        System.out.println("  Output URL: " + outputUrl);

        CodePathGuard guard = new CodePathGuard(rootPath.toString(),
                java.util.Arrays.asList(".java", ".xml"), 500, 512 * 1024);
        CodeGraphBuilder builder = new AstCodeGraphBuilder(guard, scanPackages, scanMode, skillKeywords);
        CodeGraph graph = builder.build();

        System.out.println("  Built: " + graph.getNodes().size() + " nodes, "
                + graph.getEdges().size() + " edges");

        H2CodeGraphIndex h2Index = new H2CodeGraphIndex(outputUrl);
        h2Index.loadGraph(graph);
        System.out.println("  Persisted to H2: " + h2Index.nodeCount() + " nodes");
        h2Index.close();

        System.out.println("Done. Code graph saved to: " + outputUrl);
        return 0;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            System.err.println("Error: " + option + " requires a value");
            printUsage();
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("Usage: java cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli");
        System.out.println("  --project-root <path>   Project source root to scan (required)");
        System.out.println("  --scan-mode <mode>      all | skills | packages (default: all)");
        System.out.println("  --skill-dir <path>      Skill .md directory for 'skills' mode keyword extraction");
        System.out.println("  --scan-packages <pkg>   Comma-separated package prefixes (default: all)");
        System.out.println("  --output <path-or-url>  H2 file path or JDBC URL (default: ./data/codegraph)");
        System.out.println("  --help                  Show this help");
    }
}
