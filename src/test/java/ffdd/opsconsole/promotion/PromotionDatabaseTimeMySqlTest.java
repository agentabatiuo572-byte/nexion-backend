package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.infrastructure.PromotionSqlConfiguration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** CI supplies an isolated schema URL without query parameters and its credentials. Only temporary tables are written. */
@EnabledIfEnvironmentVariable(named="NEXION_PROMOTION_TIME_MYSQL_BASE_URL",matches="jdbc:mysql:.+")
class PromotionDatabaseTimeMySqlTest {
    @ParameterizedTest
    @ValueSource(strings={"UTC","+07:00","+08:00"})
    void productionMapperRoundTripsAllConsumedTimeColumnsAndRetainsExpiryBoundaries(String zone) throws Exception {
        String url=System.getenv("NEXION_PROMOTION_TIME_MYSQL_BASE_URL")
            +"?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone="+URLEncoder.encode(zone,StandardCharsets.UTF_8)+"&forceConnectionTimeZoneToSession=true";
        try(Connection connection=DriverManager.getConnection(url,System.getenv("NEXION_PROMOTION_TIME_MYSQL_USER"),System.getenv("NEXION_PROMOTION_TIME_MYSQL_PASSWORD"))){
            var source=new SingleConnectionDataSource(connection,true);
            var jdbc=new JdbcTemplate(source);
            var db=new PromotionSqlConfiguration().promotionRawMapper(source);
            List<String> columns=PromotionDatabaseTimeMappingTest.TIME_COLUMNS;
            jdbc.execute("CREATE TEMPORARY TABLE promotion_time_probe ("+String.join(",",columns.stream().map(column->column+" DATETIME(6) NULL").toList())+")");
            Instant input=Instant.parse("2026-10-22T12:38:56.123456Z");
            db.write("INSERT INTO promotion_time_probe ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")",
                columns.stream().map(column->timestamp(input)).toArray());
            Map<String,Object> row=db.requiredRow("SELECT * FROM promotion_time_probe");
            for(String column:columns){
                assertInstanceOf(Timestamp.class,row.get(column),column);
                assertEquals(input,instant(row.get(column)),column+" / "+zone);
            }
            String wall=LocalDateTime.ofInstant(input,ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"));
            assertEquals(wall,jdbc.queryForObject("SELECT DATE_FORMAT(expires_at,'%Y-%m-%d %H:%i:%s.%f') FROM promotion_time_probe",String.class));
            Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
            assertTrue(Duration.between(instant(db.requiredRow("SELECT NOW(6) as_of").get("as_of")),now).abs().compareTo(Duration.ofSeconds(3))<0);
            db.write("UPDATE promotion_time_probe SET expires_at=?,pay_by=?,ready_at=?",timestamp(now.plusSeconds(300)),timestamp(now.plusSeconds(600)),timestamp(now.minusSeconds(1)));
            row=db.requiredRow("SELECT expires_at,pay_by,ready_at FROM promotion_time_probe");
            assertEquals(Duration.ofSeconds(300),Duration.between(now,instant(row.get("expires_at"))));
            assertTrue(instant(row.get("expires_at")).isAfter(now));
            assertTrue(instant(row.get("pay_by")).isAfter(now));
            assertFalse(instant(row.get("ready_at")).isAfter(now));
            assertEquals(1,db.count("SELECT expires_at>NOW(6) AND pay_by>NOW(6) AND ready_at<=NOW(6) FROM promotion_time_probe"));
            db.write("UPDATE promotion_time_probe SET expires_at=?",timestamp(now.minusSeconds(1)));
            assertFalse(instant(db.requiredRow("SELECT expires_at FROM promotion_time_probe").get("expires_at")).isAfter(now));
            assertEquals(1,db.count("SELECT expires_at<=NOW(6) FROM promotion_time_probe"));
            assertEquals(Set.of("expires_at"),db.requiredRow("SELECT expires_at FROM promotion_time_probe").keySet());
            assertEquals(Set.of("quantity"),db.requiredRow("SELECT 7 quantity").keySet());
            var nullable=db.requiredRow("SELECT CAST(NULL AS DATETIME) ready_at");
            assertTrue(nullable.containsKey("ready_at"));assertNull(nullable.get("ready_at"));
        }
    }
}
