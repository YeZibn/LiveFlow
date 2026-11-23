package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 为已有房间补齐对外 UUID，保留房间的原有字段。 */
public class V6__backfill_room_uuid extends BaseJavaMigration {

    private static final int BATCH_SIZE = 250;

    /**
     * 逐批回填仍为空的 UUID，已分配的值在重跑时保留。
     * @param context Flyway 提供的迁移上下文及连接
     * @throws SQLException 查询失败或遇到无法完成的更新
     */
    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (var update = connection.prepareStatement(
                "UPDATE rooms SET room_uuid = ? WHERE id = ? AND room_uuid IS NULL")) {
            long previousId = 0;
            while (true) {
                List<Long> ids = missingIds(connection, previousId);
                if (ids.isEmpty()) {
                    return;
                }
                // 按主键推进，避免每批从表头重新扫描。只更新 UUID 列。
                for (long id : ids) {
                    update.setString(1, UUID.randomUUID().toString());
                    update.setLong(2, id);
                    update.executeUpdate();
                }
                previousId = ids.getLast();
            }
        }
    }

    private static List<Long> missingIds(Connection connection, long previousId) throws SQLException {
        try (var select = connection.prepareStatement(
                "SELECT id FROM rooms WHERE id > ? AND room_uuid IS NULL ORDER BY id LIMIT ?")) {
            select.setLong(1, previousId);
            select.setInt(2, BATCH_SIZE);
            try (var result = select.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
                return ids;
            }
        }
    }
}
