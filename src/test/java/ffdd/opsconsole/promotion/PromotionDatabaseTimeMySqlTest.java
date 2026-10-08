package ffdd.opsconsole.promotion;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionDatabaseTimeMySqlTest {
    @Test void actualDatetimeRoundTripAndDatabaseNowUseTheCanonicalShanghaiSession() throws Exception {
        Map<String,String> credentials=new HashMap<>();
        for(String line:Files.readAllLines(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/mysql/client.private.ini"))){
            if(!line.contains("=")||line.strip().startsWith("#"))continue;
            String[] fields=line.split("=",2);String value=fields[1].strip();
            if(value.startsWith("\"")&&value.endsWith("\""))value=value.substring(1,value.length()-1);
            credentials.put(fields[0].strip(),value);
        }
        try(Connection connection=DriverManager.getConnection("jdbc:mysql://127.0.0.1:33339/growth_promotions_20261007?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",credentials.get("user"),credentials.get("password"))){
            JdbcTemplate jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            assertEquals("growth_promotions_20261007",jdbc.queryForObject("SELECT DATABASE()",String.class));
            assertEquals(33339,jdbc.queryForObject("SELECT @@port",Integer.class));
            jdbc.execute("SET time_zone='+08:00'");
            jdbc.execute("CREATE TEMPORARY TABLE promotion_time_probe (pay_by DATETIME(6) NOT NULL)");
            Instant input=Instant.parse("2026-10-07T12:34:56.123456Z");
            jdbc.update("INSERT INTO promotion_time_probe(pay_by) VALUES(?)",timestamp(input));
            Map<String,Object> row=jdbc.queryForMap("SELECT pay_by,DATE_FORMAT(pay_by,'%Y-%m-%d %H:%i:%s.%f') wall FROM promotion_time_probe");
            assertEquals("2026-10-07 20:34:56.123456",row.get("wall"));
            assertEquals(input,instant(row.get("pay_by")));
            assertEquals(input,instant(LocalDateTime.parse("2026-10-07T20:34:56.123456")));
            Instant now=instant(jdbc.queryForMap("SELECT NOW(6) now_value").get("now_value"));
            assertTrue(Duration.between(now,Instant.now()).abs().compareTo(Duration.ofSeconds(3))<0);
            jdbc.update("UPDATE promotion_time_probe SET pay_by=?",timestamp(now.plusSeconds(60)));
            assertEquals(1,jdbc.queryForObject("SELECT pay_by>NOW(6) FROM promotion_time_probe",Integer.class));
            jdbc.update("UPDATE promotion_time_probe SET pay_by=?",timestamp(now.minusSeconds(60)));
            assertEquals(1,jdbc.queryForObject("SELECT pay_by<=NOW(6) FROM promotion_time_probe",Integer.class));
        }
    }
}
