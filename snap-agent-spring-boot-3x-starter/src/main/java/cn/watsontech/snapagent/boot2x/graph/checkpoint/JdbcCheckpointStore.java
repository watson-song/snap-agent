package cn.watsontech.snapagent.boot2x.graph.checkpoint;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointMetadata;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * JDBC-backed {@link CheckpointStore} for cluster deployments.
 *
 * <p>Uses Spring {@link JdbcTemplate} so any JDBC DataSource works
 * (H2, MySQL, PostgreSQL, etc.). Creates the {@code snap_agent_checkpoints}
 * table automatically on first use.</p>
 *
 * <p>Unlike {@link SqliteCheckpointStore} (raw JDBC Connection, SQLite-only),
 * this implementation delegates connection management to the DataSource pool,
 * making it safe for multi-threaded / multi-node cluster deployments.</p>
 */
public class JdbcCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcCheckpointStore.class);

    // Language: SQL standard + IF NOT EXISTS (H2/MySQL/PostgreSQL compatible)
    static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS snap_agent_checkpoints ("
                    + "id VARCHAR(128) NOT NULL PRIMARY KEY,"
                    + "thread_id VARCHAR(255) NOT NULL,"
                    + "turn INT NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "node_name VARCHAR(255),"
                    + "state_data BLOB NOT NULL"
                    + ")";

    static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_checkpoints_thread ON snap_agent_checkpoints(thread_id, created_at DESC)";

    private static final String INSERT =
            "INSERT INTO snap_agent_checkpoints (id, thread_id, turn, created_at, node_name, state_data)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";

    private static final String SELECT_DATA =
            "SELECT state_data FROM snap_agent_checkpoints WHERE id = ?";

    private static final String SELECT_METADATA_BY_THREAD =
            "SELECT id, thread_id, turn, created_at, node_name"
                    + " FROM snap_agent_checkpoints WHERE thread_id = ?"
                    + " ORDER BY created_at DESC";

    private static final String DELETE_BY_ID =
            "DELETE FROM snap_agent_checkpoints WHERE id = ?";

    private static final String DELETE_BY_THREAD =
            "DELETE FROM snap_agent_checkpoints WHERE thread_id = ?";

    private final JdbcTemplate jdbc;
    private volatile boolean tableReady;

    public JdbcCheckpointStore(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate must not be null");
        }
        this.jdbc = jdbc;
        ensureTable();
    }

    private void ensureTable() {
        if (tableReady) return;
        try {
            jdbc.execute(CREATE_TABLE);
            jdbc.execute(CREATE_INDEX);
            tableReady = true;
            log.info("snap_agent_checkpoints table ready");
        } catch (Exception e) {
            log.warn("Failed to create checkpoints table (may already exist): {}", e.getMessage());
            // Mark as ready anyway — the table likely exists from a previous run
            // and the IF NOT EXISTS clause was rejected by the DB dialect.
            tableReady = true;
        }
    }

    @Override
    public String save(String threadId, GraphState state) {
        String id = UUID.randomUUID().toString();
        jdbc.update(INSERT, id, threadId, state.getTurn(), System.currentTimeMillis(),
                (String) null, state.serialize());
        log.debug("Saved checkpoint {} for thread {}", id, threadId);
        return id;
    }

    @Override
    public GraphState load(String checkpointId) {
        try {
            byte[] data = jdbc.queryForObject(SELECT_DATA, byte[].class, checkpointId);
            return data != null ? GraphState.deserialize(data) : null;
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public List<CheckpointMetadata> list(String threadId) {
        return jdbc.query(SELECT_METADATA_BY_THREAD,
                (rs, rowNum) -> new CheckpointMetadata(
                        rs.getString("id"),
                        rs.getString("thread_id"),
                        rs.getInt("turn"),
                        rs.getLong("created_at"),
                        rs.getString("node_name")
                ),
                threadId);
    }

    @Override
    public void delete(String checkpointId) {
        jdbc.update(DELETE_BY_ID, checkpointId);
    }

    @Override
    public void deleteByThread(String threadId) {
        jdbc.update(DELETE_BY_THREAD, threadId);
    }
}
