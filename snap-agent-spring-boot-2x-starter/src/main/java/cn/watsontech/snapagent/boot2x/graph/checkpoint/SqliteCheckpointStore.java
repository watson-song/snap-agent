package cn.watsontech.snapagent.boot2x.graph.checkpoint;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointMetadata;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite-backed CheckpointStore for development environment.
 * Zero external dependencies (SQLite is embedded).
 */
public class SqliteCheckpointStore implements CheckpointStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteCheckpointStore.class);
    private final String dbPath;
    private Connection connection;

    public SqliteCheckpointStore(String dbPath) {
        this.dbPath = dbPath;
    }

    public void init() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS checkpoints (" +
                    "  id TEXT PRIMARY KEY," +
                    "  thread_id TEXT NOT NULL," +
                    "  turn INTEGER NOT NULL," +
                    "  created_at INTEGER NOT NULL," +
                    "  node_name TEXT," +
                    "  data BLOB NOT NULL" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_thread ON checkpoints(thread_id, created_at DESC)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("SqliteCheckpointStore init failed", e);
        }
    }

    @Override
    public String save(String threadId, GraphState state) {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO checkpoints (id, thread_id, turn, created_at, node_name, data) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, threadId);
            ps.setInt(3, state.getTurn());
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, null);
            ps.setBytes(6, state.serialize());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("checkpoint save failed", e);
        }
        return id;
    }

    @Override
    public GraphState load(String checkpointId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT data FROM checkpoints WHERE id = ?")) {
            ps.setString(1, checkpointId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return GraphState.deserialize(rs.getBytes("data"));
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("checkpoint load failed", e);
        }
    }

    @Override
    public List<CheckpointMetadata> list(String threadId) {
        List<CheckpointMetadata> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT id, thread_id, turn, created_at, node_name FROM checkpoints WHERE thread_id = ? ORDER BY created_at DESC")) {
            ps.setString(1, threadId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new CheckpointMetadata(
                    rs.getString("id"),
                    rs.getString("thread_id"),
                    rs.getInt("turn"),
                    rs.getLong("created_at"),
                    rs.getString("node_name")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("checkpoint list failed", e);
        }
        return result;
    }

    @Override
    public void delete(String checkpointId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM checkpoints WHERE id = ?")) {
            ps.setString(1, checkpointId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("checkpoint delete failed", e);
        }
    }

    @Override
    public void deleteByThread(String threadId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM checkpoints WHERE thread_id = ?")) {
            ps.setString(1, threadId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("checkpoint deleteByThread failed", e);
        }
    }

    public void close() {
        try {
            if (connection != null) connection.close();
        } catch (SQLException e) {
            log.warn("failed to close sqlite connection", e);
        }
    }
}
