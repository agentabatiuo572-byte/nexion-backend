package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.infrastructure.H4EventSqlConfiguration;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="H4_EVENT_JDBC_URL",matches=".+")
class H4EventTimeMySqlIntegrationTest {
    @Test @ResourceLock("java.util.TimeZone.default")
    void utcJvmAndShanghaiConnectionKeepActivationAndWindowInTheSameUtcWallTime() throws Exception {
        String url=System.getenv("H4_EVENT_JDBC_URL").split("\\?",2)[0]
                +"?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08%3A00&forceConnectionTimeZoneToSession=true";
        TimeZone original=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            try(var connection=DriverManager.getConnection(url,System.getenv("H4_EVENT_DB_USER"),System.getenv("H4_EVENT_DB_PASSWORD"))){
                var source=new SingleConnectionDataSource(connection,true);var jdbc=new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).startsWith("h4_events_");
                assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+08:00");
                var mapper=new H4EventSqlConfiguration().h4EventRawMapper(source);
                var columns=H4EventTimeMappingTest.TIME_COLUMNS;
                jdbc.execute("CREATE TEMPORARY TABLE h4_time_probe ("+String.join(",",columns.stream().map(column->column+" DATETIME(6) NULL").toList())+")");
                LocalDateTime wall=LocalDateTime.parse("2026-10-25T12:00:00.123456");
                mapper.write("INSERT INTO h4_time_probe ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")",
                        columns.stream().map(column->wall).toArray());
                var row=mapper.list("SELECT * FROM h4_time_probe").get(0);
                for(String column:columns)assertThat(row.get(column)).as(column).isEqualTo(wall);
                assertThat(jdbc.queryForObject("SELECT activated_at=starts_at AND activated_at=ends_at FROM h4_time_probe",Integer.class)).isEqualTo(1);
                mapper.write("UPDATE h4_time_probe SET disabled_at=NULL");
                assertThat(mapper.list("SELECT disabled_at FROM h4_time_probe").get(0)).containsEntry("disabled_at",null);
            }
        } finally {TimeZone.setDefault(original);}
    }
}
