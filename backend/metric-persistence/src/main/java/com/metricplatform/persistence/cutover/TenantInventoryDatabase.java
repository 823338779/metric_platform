package com.metricplatform.persistence.cutover;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** V18 冻结窗口的离线入口，此时旧库尚无租户列。 */
public final class TenantInventoryDatabase {
    private TenantInventoryDatabase() {}

    interface InventoryMapper {
        @Select("""
                SELECT EXISTS (SELECT 1 FROM pg_attribute
                               WHERE attrelid = 'execution_environment'::regclass
                                 AND attname = 'tenant_id' AND NOT attisdropped)
                """)
        boolean alreadyTenantScoped();

        @Select("SELECT environment_id::text FROM execution_environment ORDER BY environment_id")
        List<String> environments();

        @Update("""
                CREATE TABLE IF NOT EXISTS tenant_cutover_input
                    (manager_login text, environment_ids uuid[], builds jsonb)
                """)
        void createInventory();

        @Update("LOCK TABLE tenant_cutover_input IN ACCESS EXCLUSIVE MODE")
        void lockInventory();

        @Delete("DELETE FROM tenant_cutover_input")
        void clearInventory();

        @Insert("""
                INSERT INTO tenant_cutover_input (manager_login, environment_ids, builds)
                SELECT input->>'manager', ARRAY(
                    SELECT jsonb_array_elements_text(input->'environments')::uuid
                ), input->'builds' FROM (SELECT CAST(#{inventory} AS jsonb) AS input) snapshot
                """)
        void insertInventory(@Param("inventory") String inventory);
    }

    public static void main(String[] args) throws Exception {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("请通过 SPRING_DATASOURCE_URL 指定待升级的平台库");
        }
        var source = new UnpooledDataSource("org.postgresql.Driver", url,
                System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
        var config = new Configuration(new Environment("cutover", new JdbcTransactionFactory(), source));
        config.addMapper(InventoryMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            var mapper = session.getMapper(InventoryMapper.class);
            if (mapper.alreadyTenantScoped()) {
                throw new IllegalStateException("该数据库已启用租户隔离，不能运行升级前盘点");
            }
            switch (args[0]) {
                case "environments" -> mapper.environments().forEach(System.out::println);
                case "replace" -> {
                    String inventory = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                    mapper.createInventory();
                    mapper.lockInventory();
                    mapper.clearInventory();
                    mapper.insertInventory(inventory);
                    session.commit();
                }
                default -> throw new IllegalArgumentException("未知的盘点操作");
            }
        }
    }
}
