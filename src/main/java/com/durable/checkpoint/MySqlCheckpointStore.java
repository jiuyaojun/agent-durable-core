package com.durable.checkpoint;

import com.durable.json.Json;
import com.fasterxml.jackson.core.type.TypeReference;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 检查点的 MySQL 存储。
 *
 * CV 性质要求：非法状态必须在【写之前】就被拒绝，不得先写进去再忽略。
 * 因此 save() 的第一件事是重新校验 —— 即使调用方绕过 record 构造器，
 * 或者从别处反序列化出一个非法对象，这里也拦得住。连接都还没打开就已经抛错了。
 */
public class MySqlCheckpointStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private static final String SQL_INSERT =
            "INSERT INTO checkpoint "
                    + "(workflow_id, version, frontier_step, schema_version, state, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String COLUMNS =
            "SELECT workflow_id, version, frontier_step, schema_version, state, created_at "
                    + "FROM checkpoint WHERE workflow_id = ? ";

    private static final String SQL_LATEST = COLUMNS + "ORDER BY version DESC LIMIT 1";
    private static final String SQL_ALL = COLUMNS + "ORDER BY version ASC";

    private final DataSource dataSource;

    public MySqlCheckpointStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void save(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        validate(checkpoint);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
            ps.setString(1, checkpoint.workflowId());
            ps.setInt(2, checkpoint.version());
            ps.setInt(3, checkpoint.frontierStep());
            ps.setString(4, checkpoint.schemaVersion());
            ps.setString(5, Json.write(checkpoint.results()));
            ps.setTimestamp(6, Timestamp.from(checkpoint.createdAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入检查点失败: " + checkpoint.workflowId(), e);
        }
    }

    /** CV 的落点：校验不过就抛错，一个字都不写。 */
    private static void validate(Checkpoint checkpoint) {
        if (checkpoint.results().size() != checkpoint.frontierStep() + 1) {
            throw new InvalidCheckpointException(
                    "拒绝持久化非法检查点: frontierStep=" + checkpoint.frontierStep()
                            + " 与 results.size()=" + checkpoint.results().size() + " 不一致");
        }
        if (!Checkpoint.CURRENT_SCHEMA.equals(checkpoint.schemaVersion())) {
            throw new InvalidCheckpointException(
                    "不支持的 schema 版本: " + checkpoint.schemaVersion());
        }
    }

    public Optional<Checkpoint> latest(String workflowId) {
        return query(SQL_LATEST, workflowId).stream().findFirst();
    }

    public List<Checkpoint> loadAll(String workflowId) {
        return query(SQL_ALL, workflowId);
    }

    private List<Checkpoint> query(String sql, String workflowId) {
        List<Checkpoint> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Checkpoint(
                            rs.getString("workflow_id"),
                            rs.getInt("version"),
                            rs.getInt("frontier_step"),
                            rs.getString("schema_version"),
                            Json.read(rs.getString("state"), STRING_LIST),
                            rs.getTimestamp("created_at").toInstant()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取检查点失败: " + workflowId, e);
        }
        return result;
    }
}
