import java.sql.*;

public final class StarRocksReadOnlyProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection connection=DriverManager.getConnection(System.getenv("STARROCKS_JDBC_URL"),System.getenv("STARROCKS_USERNAME"),System.getenv("STARROCKS_PASSWORD"))) {
            connection.setReadOnly(true);
            int objects=0;
            try(ResultSet rs=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE","VIEW"})){while(rs.next())objects++;}
            long rows;
            try(Statement statement=connection.createStatement()){statement.setQueryTimeout(10);try(ResultSet rs=statement.executeQuery("SELECT COUNT(*) FROM `fact_orders`")){rs.next();rows=rs.getLong(1);}}
            if(objects<1||rows<1)throw new IllegalStateException("StarRocks fixture is empty");
            System.out.println("STARROCKS_READ_ONLY_OK objects="+objects+" factOrders="+rows);
        }
    }
}
