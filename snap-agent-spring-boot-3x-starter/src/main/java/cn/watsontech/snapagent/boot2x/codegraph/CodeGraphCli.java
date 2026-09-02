package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone CLI for pre-building a code graph into an H2 file database.
 *
 * <p>Intended for CI/CD pipelines where the source tree is available at build
 * time but not at runtime (e.g. k8s deployments). Run this during CI to produce
 * a {@code codegraph.mv.db} file, then bake it into the Docker image.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -cp snap-agent-spring-boot-2x-starter.jar:snap-agent-core.jar:h2.jar \
 *     cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli \
 *     --project-root /path/to/project \
 *     --scan-packages com.example \
 *     --output ./data/codegraph
 * </pre>
 *
 * <p>Then in k8s, configure {@code snap-agent.code-graph.persistence=h2}
 * and {@code snap-agent.code-graph.h2-url=jdbc:h2:file:./data/codegraph}.
 * The application will load the pre-built graph from disk without scanning.</p>
 */
public class CodeGraphCli {

    public static void main(String[] args) throws Exception {
        String projectRoot = null;
        String outputUrl = "jdbc:h2:file:./data/codegraph";
        List<String> scanPackages = new ArrayList<String>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--project-root":
                    projectRoot = args[++i];
                    break;
                case "--scan-packages":
                    for (String pkg : args[++i].split(",")) {
                        String trimmed = pkg.trim();
                        if (!trimmed.isEmpty()) {
                            scanPackages.add(trimmed);
                        }
                    }
                    break;
                case "--output":
                    String output = args[++i];
                    if (!output.startsWith("jdbc:h2:")) {
                        output = "jdbc:h2:file:" + output;
                    }
                    outputUrl = output;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }

        if (projectRoot == null) {
            System.err.println("Error: --project-root is required");
            printUsage();
            System.exit(1);
        }

        Path rootPath = Paths.get(projectRoot).toAbsolutePath().normalize();
        if (!java.nio.file.Files.exists(rootPath)) {
            System.err.println("Error: project root does not exist: " + rootPath);
            System.exit(1);
        }

        System.out.println("Building code graph...");
        System.out.println("  Project root: " + rootPath);
        System.out.println("  Scan packages: " + (scanPackages.isEmpty() ? "(all)" : scanPackages));
        System.out.println("  Output URL: " + outputUrl);

        CodePathGuard guard = new CodePathGuard(rootPath.toString(),
                java.util.Arrays.asList(".java", ".xml"), 500, 512 * 1024);
        CodeGraphBuilder builder = new SimpleCodeGraphBuilder(guard, scanPackages);
        CodeGraph graph = builder.build();

        System.out.println("  Built: " + graph.getNodes().size() + " nodes, "
                + graph.getEdges().size() + " edges");

        H2CodeGraphIndex h2Index = new H2CodeGraphIndex(outputUrl);
        h2Index.loadGraph(graph);
        System.out.println("  Persisted to H2: " + h2Index.nodeCount() + " nodes");
        h2Index.close();

        System.out.println("Done. Code graph saved to: " + outputUrl);
    }

    private static void printUsage() {
        System.out.println("Usage: java cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli");
        System.out.println("  --project-root <path>    Project source root to scan (required)");
        System.out.println("  --scan-packages <pkg>     Comma-separated package prefixes (optional, default: all)");
        System.out.println("  --output <path-or-url>   H2 file path or JDBC URL (default: ./data/codegraph)");
        System.out.println("  --help                   Show this help");
    }
}
