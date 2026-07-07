package io.aster.test;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 测试用 blocking DB 辅助（#57）。生产已移除 reactive 持久化，测试的 DB setup/cleanup 也
 * 统一到 blocking JDBC（Agroal {@link DataSource}），彻底不依赖 reactive-pg-client。
 *
 * <p>提供覆盖原 vertx {@code Pool} 用法的最小 API：无结果的 DML（DELETE/UPDATE/INSERT）、
 * 带参 DML、SELECT 遍历行、以及取单值。每次调用自获取/释放连接（try-with-resources），
 * autocommit，语义与原 {@code pgPool.query(...).execute().await()} 一致。
 */
@ApplicationScoped
public class BlockingDbTestHelper {

    @Inject
    DataSource dataSource;

    /** 执行一条无结果 DML（DELETE/UPDATE/INSERT），返回受影响行数。 */
    public int execute(String sql, Object... params) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("test DB execute failed: " + sql, e);
        }
    }

    /** 执行 SELECT，对每一行调用 consumer（consumer 从 {@link Row} 读列）。 */
    public void query(String sql, Consumer<Row> rowConsumer, Object... params) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rowConsumer.accept(new Row(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("test DB query failed: " + sql, e);
        }
    }

    /** 执行 SELECT 并返回所有行（列名→值 的有序 map 列表）。 */
    public List<Map<String, Object>> queryList(String sql, Object... params) {
        List<Map<String, Object>> out = new ArrayList<>();
        query(sql, row -> out.add(row.asMap()), params);
        return out;
    }

    /** 取单行单列 long（如 count(*)）；无行返回 0。 */
    public long queryLong(String sql, Object... params) {
        long[] result = {0L};
        boolean[] seen = {false};
        query(sql, row -> {
            if (!seen[0]) {
                result[0] = row.getLong(1);
                seen[0] = true;
            }
        }, params);
        return result[0];
    }

    /** 取单行单列 String（第 1 列）；无行返回 null。 */
    public String queryString(String sql, Object... params) {
        String[] result = {null};
        boolean[] seen = {false};
        query(sql, row -> {
            if (!seen[0]) {
                result[0] = row.getString(1);
                seen[0] = true;
            }
        }, params);
        return result[0];
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** 单行读取包装，按列名或列序号取值。 */
    public static final class Row {
        private final ResultSet rs;

        Row(ResultSet rs) {
            this.rs = rs;
        }

        public String getString(String col) {
            try { return rs.getString(col); } catch (SQLException e) { throw wrap(e); }
        }

        public String getString(int idx) {
            try { return rs.getString(idx); } catch (SQLException e) { throw wrap(e); }
        }

        public long getLong(String col) {
            try { return rs.getLong(col); } catch (SQLException e) { throw wrap(e); }
        }

        public long getLong(int idx) {
            try { return rs.getLong(idx); } catch (SQLException e) { throw wrap(e); }
        }

        public Object get(String col) {
            try { return rs.getObject(col); } catch (SQLException e) { throw wrap(e); }
        }

        Map<String, Object> asMap() {
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                var md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    m.put(md.getColumnLabel(i), rs.getObject(i));
                }
                return m;
            } catch (SQLException e) {
                throw wrap(e);
            }
        }

        private static RuntimeException wrap(SQLException e) {
            return new RuntimeException("test DB row read failed", e);
        }
    }
}
