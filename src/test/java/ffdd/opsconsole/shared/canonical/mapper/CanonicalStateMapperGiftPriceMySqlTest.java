package ffdd.opsconsole.shared.canonical.mapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.math.BigDecimal;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reuses the promotion CI isolated database credentials; writes only connection-local temporary tables. */
@EnabledIfEnvironmentVariable(named="NEXION_PROMOTION_TIME_MYSQL_BASE_URL",matches="jdbc:mysql:.+")
class CanonicalStateMapperGiftPriceMySqlTest {
    @ParameterizedTest
    @ValueSource(strings={"ownedDevices","developmentOwnedDevices"})
    void giftsStayFreeBeforeAndAfterPrincipalRefundWhileLegacyDeviceFallbacksRemain(String method) throws Exception {
        String sql=String.join("\n",CanonicalStateMapper.class.getMethod(method,Long.class).getAnnotation(Select.class).value());
        String preceding="d.dc_location AS location,";
        String expression=sql.substring(sql.indexOf(preceding)+preceding.length(),sql.indexOf(" AS actualPaidUsdt"));
        String url=System.getenv("NEXION_PROMOTION_TIME_MYSQL_BASE_URL")+"?useSSL=false&allowPublicKeyRetrieval=true";
        try(Connection connection=DriverManager.getConnection(url,System.getenv("NEXION_PROMOTION_TIME_MYSQL_USER"),System.getenv("NEXION_PROMOTION_TIME_MYSQL_PASSWORD"))){
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            jdbc.execute("CREATE TEMPORARY TABLE canonical_gift_price_device(id INT PRIMARY KEY,source_channel VARCHAR(32),price_usdt_snapshot DECIMAL(18,6),source_order_no VARCHAR(32))");
            jdbc.execute("CREATE TEMPORARY TABLE canonical_gift_price_order(order_no VARCHAR(32),quantity INT,amount_usdt DECIMAL(18,6),payment_status VARCHAR(32))");
            jdbc.execute("CREATE TEMPORARY TABLE canonical_gift_price_product(price_usdt DECIMAL(18,6))");
            jdbc.update("INSERT INTO canonical_gift_price_product VALUES(1299.000000)");
            jdbc.update("INSERT INTO canonical_gift_price_order VALUES('REFUNDED',1,19.900000,'REFUNDED'),('PAID',1,19.900000,'PAID'),('BUNDLE',2,100.000000,'PAID')");
            jdbc.update("""
                INSERT INTO canonical_gift_price_device VALUES
                (1,'PROMOTION_GIFT',0,'REFUNDED'),
                (2,'PROMOTION_GIFT',0,'PAID'),
                (3,'PROMOTION_GIFT',0,NULL),
                (4,'ORDER',0,NULL),
                (5,'ORDER',0,'BUNDLE'),
                (6,'ORDER',25.000000,NULL),
                (7,NULL,0,NULL),
                (8,'promotion_gift',1299.000000,'PAID')
                """);
            Map<Integer,BigDecimal> expected=Map.of(1,BigDecimal.ZERO,2,BigDecimal.ZERO,3,BigDecimal.ZERO,
                4,new BigDecimal("1299"),5,new BigDecimal("50"),6,new BigDecimal("25"),7,new BigDecimal("1299"),8,BigDecimal.ZERO);
            var rows=jdbc.queryForList("SELECT d.id,"+expression+" AS actualPaidUsdt FROM canonical_gift_price_device d CROSS JOIN canonical_gift_price_product p LEFT JOIN canonical_gift_price_order o ON o.order_no=d.source_order_no AND o.payment_status='PAID' ORDER BY d.id");
            assertEquals(expected.size(),rows.size());
            for(var row:rows){
                int id=((Number)row.get("id")).intValue();
                assertEquals(0,expected.get(id).compareTo((BigDecimal)row.get("actualPaidUsdt")),method+" / device "+id);
            }
        }
    }
}
