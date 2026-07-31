package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent {@link CodeGraphIndex} backed by an H2 database.
 *
 * <p>Stores nodes and edges in H2 tables ({@code CODE_GRAPH_NODES},
 * {@code CODE_GRAPH_EDGES}) with indexes on edge endpoints and node names.
 * Supports both in-memory (default: {@code jdbc:h2:mem:snapagent-codegraph})
 * and file-based ({@code jdbc:h2:file:./data/codegraph}) JDBC URLs, enabling
 * the code graph to survive process restarts when a file URL is used.</p>
 *
 * <p>All graph searches (call chain, reverse call chain, impact scope) use
 * BFS with cycle detection, delegating edge lookups to SQL queries. The
 * single JDBC connection is guarded by {@code synchronized} so the index
 * is safe for concurrent reads.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   H2CodeGraphIndex index = new H2CodeGraphIndex("jdbc:h2:file:./data/codegraph");
 *   index.loadGraph(graph);
 *   List&lt;CodeGraphNode&gt; chain = index.findCallChain("com.example.Foo#bar()", 5);
 *   index.close();
 * </pre>
 */
public class H2CodeGraphIndex implements CodeGraphIndex {

    /** Default in-memory JDBC URL (keeps the DB alive while the JVM runs). */
    public static final String DEFAULT_URL = "jdbc:h2:mem:snapagent-codegraph;DB_CLOSE_DELAY=-1";

    private final Connection connection;

    /**
     * Construct an index using the default in-memory URL.
     * Tables are created immediately but left empty; call
     * {@link #loadGraph(CodeGraph)} to populate.
     */
    public H2CodeGraphIndex() {
        this(DEFAULT_URL);
    }

    /**
     * Construct an index with a custom JDBC URL.
     *
     * @param jdbcUrl H2 JDBC URL, e.g. {@code jdbc:h2:mem:test} for in-memory
     *                or {@code jdbc:h2:file:./data/codegraph} for persistent
     */
    public H2CodeGraphIndex(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            throw new IllegalArgumentException("jdbcUrl must not be null or empty");
        }
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "H2 database driver (com.h2database:h2) not found on classpath. "
                            + "Add the H2 dependency to use H2CodeGraphIndex.", e);
        }
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            this.connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open H2 connection: " + jdbcUrl, e);
        }
        createTables();
    }

    // ------------------------------------------------------------------
    // Schema management
    // ------------------------------------------------------------------

    private void createTables() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS CODE_GRAPH_NODES ("
                    + "ID VARCHAR PRIMARY KEY, "
                    + "TYPE VARCHAR, "
                    + "NAME VARCHAR, "
                    + "PACKAGE VARCHAR, "
                    + "CLASS_NAME VARCHAR, "
                    + "RETURN_TYPE VARCHAR, "
                    + "FILE_PATH VARCHAR, "
                    + "LINE_NUMBER INT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS CODE_GRAPH_EDGES ("
                    + "FROM_ID VARCHAR, "
                    + "TO_ID VARCHAR, "
                    + "EDGE_TYPE VARCHAR, "
                    + "CONTEXT VARCHAR)");
            stmt.execute("CREATE INDEX IF NOT EXISTS IDX_EDGES_FROM ON CODE_GRAPH_EDGES(FROM_ID)");
            stmt.execute("CREATE INDEX IF NOT EXISTS IDX_EDGES_TO ON CODE_GRAPH_EDGES(TO_ID)");
            stmt.execute("CREATE INDEX IF NOT EXISTS IDX_NODES_NAME ON CODE_GRAPH_NODES(NAME)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create H2 tables", e);
        }
    }

    // ------------------------------------------------------------------
    // Bulk load / rebuild
    // ------------------------------------------------------------------

    /**
     * Bulk-load a {@link CodeGraph} into the database.
     *
     * <p>Existing data is cleared first (DELETE) so the call is idempotent
     * and safe to invoke multiple times. Node and edge inserts are batched
     * within a single transaction for performance.</p>
     *
     * @param graph the graph to load (null is a no-op)
     */
    public synchronized void loadGraph(CodeGraph graph) {
        if (graph == null) {
            return;
        }
        try {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DELETE FROM CODE_GRAPH_EDGES");
                stmt.execute("DELETE FROM CODE_GRAPH_NODES");
            }
            // Batch-insert nodes (MERGE to handle duplicate IDs from the builder)
            try (PreparedStatement ps = connection.prepareStatement(
                    "MERGE INTO CODE_GRAPH_NODES "
                            + "(ID, TYPE, NAME, PACKAGE, CLASS_NAME, RETURN_TYPE, FILE_PATH, LINE_NUMBER) "
                            + "KEY(ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (CodeGraphNode node : graph.getNodes()) {
                    ps.setString(1, node.getId());
                    ps.setString(2, node.getType() != null ? node.getType().name() : null);
                    ps.setString(3, node.getName());
                    ps.setString(4, node.getPackageName());
                    ps.setString(5, node.getClassName());
                    ps.setString(6, node.getReturnType());
                    ps.setString(7, node.getFilePath());
                    ps.setInt(8, node.getLineNumber());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            // Batch-insert edges (MERGE to handle duplicates)
            try (PreparedStatement ps = connection.prepareStatement(
                    "MERGE INTO CODE_GRAPH_EDGES "
                            + "(FROM_ID, TO_ID, EDGE_TYPE, CONTEXT) "
                            + "KEY(FROM_ID, TO_ID, EDGE_TYPE) VALUES (?, ?, ?, ?)")) {
                for (CodeGraphEdge edge : graph.getEdges()) {
                    ps.setString(1, edge.getFromId());
                    ps.setString(2, edge.getToId());
                    ps.setString(3, edge.getType() != null ? edge.getType().name() : null);
                    ps.setString(4, edge.getContext());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // best-effort rollback
            }
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // best-effort restore auto-commit
            }
            throw new IllegalStateException("Failed to load graph into H2", e);
        }
    }

    /**
     * Rebuild the index from a fresh {@link CodeGraphBuilder#build()} result.
     *
     * <p>Clears all existing tables and reloads from the builder's output.
     * Safe to call at any time; concurrent readers see a consistent snapshot
     * because the DELETE+INSERT is wrapped in a transaction.</p>
     *
     * @param builder the builder to invoke {@code build()} on
     */
    @Override
    public synchronized void rebuild(CodeGraphBuilder builder) {
        CodeGraph graph = builder.build();
        loadGraph(graph);
    }

    // ------------------------------------------------------------------
    // CodeGraphIndex implementation
    // ------------------------------------------------------------------

    @Override
    public synchronized List<CodeGraphNode> findByName(String namePattern) {
        if (namePattern == null || namePattern.isEmpty()) {
            return Collections.emptyList();
        }
        String likePattern = "%" + namePattern.toLowerCase() + "%";
        List<CodeGraphNode> results = new ArrayList<CodeGraphNode>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ID, TYPE, NAME, PACKAGE, CLASS_NAME, RETURN_TYPE, FILE_PATH, LINE_NUMBER "
                        + "FROM CODE_GRAPH_NODES WHERE LOWER(NAME) LIKE ?")) {
            ps.setString(1, likePattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapNode(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query nodes by name: " + namePattern, e);
        }
        return results;
    }

    @Override
    public synchronized CodeGraphNode getNode(String id) {
        if (id == null) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ID, TYPE, NAME, PACKAGE, CLASS_NAME, RETURN_TYPE, FILE_PATH, LINE_NUMBER "
                        + "FROM CODE_GRAPH_NODES WHERE ID = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapNode(rs);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get node by id: " + id, e);
        }
        return null;
    }

    @Override
    public synchronized List<CodeGraphEdge> getOutgoingEdges(String nodeId) {
        if (nodeId == null) {
            return Collections.emptyList();
        }
        List<CodeGraphEdge> results = new ArrayList<CodeGraphEdge>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT FROM_ID, TO_ID, EDGE_TYPE, CONTEXT "
                        + "FROM CODE_GRAPH_EDGES WHERE FROM_ID = ?")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapEdge(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query outgoing edges for: " + nodeId, e);
        }
        return results;
    }

    @Override
    public synchronized List<CodeGraphEdge> getIncomingEdges(String nodeId) {
        if (nodeId == null) {
            return Collections.emptyList();
        }
        List<CodeGraphEdge> results = new ArrayList<CodeGraphEdge>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT FROM_ID, TO_ID, EDGE_TYPE, CONTEXT "
                        + "FROM CODE_GRAPH_EDGES WHERE TO_ID = ?")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapEdge(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query incoming edges for: " + nodeId, e);
        }
        return results;
    }

    @Override
    public synchronized List<CodeGraphNode> findCallChain(String methodId, int maxDepth) {
        return bfs(methodId, maxDepth, true, CodeGraphEdge.EdgeType.CALLS);
    }

    @Override
    public synchronized List<CodeGraphNode> findReverseCallChain(String methodId, int maxDepth) {
        return bfs(methodId, maxDepth, false, CodeGraphEdge.EdgeType.CALLS);
    }

    @Override
    public synchronized List<CodeGraphNode> findImpactScope(String nodeId, int maxDepth) {
        // Impact analysis: BFS along ALL incoming edges (callers, dependents, etc.)
        return bfs(nodeId, maxDepth, false, null);
    }

    @Override
    public synchronized int nodeCount() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM CODE_GRAPH_NODES")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count nodes", e);
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // BFS traversal (uses SQL for edge lookups)
    // ------------------------------------------------------------------

    /**
     * BFS traversal from a starting node.
     *
     * @param startId            starting node ID
     * @param maxDepth           maximum BFS depth
     * @param forward            true = follow outgoing edges, false = follow incoming edges
     * @param edgeTypeFilter     filter edges by this type, or null for all edge types
     * @return ordered list of reachable nodes (excluding the starting node)
     */
    private List<CodeGraphNode> bfs(String startId, int maxDepth, boolean forward,
                                    CodeGraphEdge.EdgeType edgeTypeFilter) {
        if (startId == null || getNode(startId) == null) {
            return Collections.emptyList();
        }

        List<CodeGraphNode> result = new ArrayList<CodeGraphNode>();
        Set<String> visited = new HashSet<String>();
        visited.add(startId);

        List<String> currentLevel = new ArrayList<String>();
        currentLevel.add(startId);

        for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty(); depth++) {
            List<String> nextLevel = new ArrayList<String>();
            for (String nodeId : currentLevel) {
                List<CodeGraphEdge> edges = forward
                        ? getOutgoingEdges(nodeId)
                        : getIncomingEdges(nodeId);
                for (CodeGraphEdge edge : edges) {
                    if (edgeTypeFilter != null && edge.getType() != edgeTypeFilter) {
                        continue;
                    }
                    String targetId = forward ? edge.getToId() : edge.getFromId();
                    if (!visited.contains(targetId)) {
                        visited.add(targetId);
                        nextLevel.add(targetId);
                        CodeGraphNode target = getNode(targetId);
                        if (target != null) {
                            result.add(target);
                        }
                    }
                }
            }
            currentLevel = nextLevel;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // ResultSet mappers
    // ------------------------------------------------------------------

    private CodeGraphNode mapNode(ResultSet rs) throws SQLException {
        String typeStr = rs.getString("TYPE");
        CodeGraphNode.NodeType type = typeStr != null ? CodeGraphNode.NodeType.valueOf(typeStr) : null;
        return new CodeGraphNode(
                rs.getString("ID"),
                type,
                rs.getString("NAME"),
                rs.getString("PACKAGE"),
                rs.getString("CLASS_NAME"),
                rs.getString("RETURN_TYPE"),
                rs.getString("FILE_PATH"),
                rs.getInt("LINE_NUMBER"));
    }

    private CodeGraphEdge mapEdge(ResultSet rs) throws SQLException {
        String typeStr = rs.getString("EDGE_TYPE");
        CodeGraphEdge.EdgeType type = typeStr != null ? CodeGraphEdge.EdgeType.valueOf(typeStr) : null;
        return new CodeGraphEdge(
                rs.getString("FROM_ID"),
                rs.getString("TO_ID"),
                type,
                rs.getString("CONTEXT"));
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Close the underlying JDBC connection and release H2 resources.
     * After calling this method the index can no longer be queried.
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to close H2 connection", e);
            }
        }
    }
}
