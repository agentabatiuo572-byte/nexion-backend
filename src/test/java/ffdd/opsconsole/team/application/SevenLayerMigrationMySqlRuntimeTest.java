package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Fresh and forward upgrades use the actual MySQL client, including DELIMITER in the baseline. */
@EnabledIfEnvironmentVariable(named="DIRECT_REFERRAL_RUNTIME",matches="1")
class SevenLayerMigrationMySqlRuntimeTest {
    private static final String MARKER="-- One order owns one immutable generation";

    @Test void freshAndOldDatabasesHaveTheSameForwardContractAndRepeatsPreservePerAssetFacts() throws Exception {
        Path evidence=Path.of(System.getenv().getOrDefault("DIRECT_REFERRAL_EVIDENCE_DIR","target/direct-referral-runtime"));
        Files.createDirectories(evidence);
        String schema=Files.readString(Path.of("scripts/schema.sql"));
        String migration=Files.readString(Path.of("scripts/migrations/20261006_seven_layer_direct_split.sql"));
        assertThat(schema.substring(schema.indexOf(MARKER)).trim()).isEqualTo(migration.trim());
        assertThat(Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"))).contains("20261006_seven_layer_direct_split.sql");
        var results=new LinkedHashMap<String,Object>();
        for(String mode:new String[]{"fresh","old"}) {
            String database="seven_mig_20261006_"+mode+"_"+UUID.randomUUID().toString().replace("-","");
            assertThat(database).matches("seven_mig_20261006_(fresh|old)_[a-f0-9]{32}");
            String baseline="old".equals(mode)?schema.substring(0,schema.indexOf(MARKER)):schema;
            baseline=baseline.replace("CREATE DATABASE IF NOT EXISTS nexion ","CREATE DATABASE IF NOT EXISTS "+database+" ").replace("USE nexion;","USE "+database+";");
            runMysql(evidence,database,"baseline",baseline);
            var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33335/"+database+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",""));
            assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33335);
            assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(database);
            if("old".equals(mode)) {
                jdbc.update("INSERT INTO nx_direct_referral_settlement(settlement_no,source_environment,source_type,source_ref,source_user_id,beneficiary_user_id,source_occurred_at,policy_version,policy_snapshot,basis_usdt,amount_usdt,amount_nex,status,release_at,credited_at,reversal_recorded) VALUES('old-paid','PRODUCTION','direct_purchase','old-paid-order',1,2,NOW(),1,'{}',100,50,100,'REVERSED',NOW(),NOW(),1)");
            }
            runMysql(evidence,database,"forward",migration);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_direct_referral_settlement' AND COLUMN_NAME IN ('layer_no','price_locked_at','credited_usdt_at','credited_nex_at','cancelled_usdt','cancelled_nex')",Integer.class)).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('nx_unilevel_order_settlement','nx_unilevel_event_recovery')",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_direct_referral_settlement' AND INDEX_NAME='uk_direct_source' AND COLUMN_NAME='layer_no'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_config_item WHERE config_key='team.seven-layer.cutover-at'",Integer.class)).isZero();
            if("old".equals(mode)) {
                var old=jdbc.queryForMap("SELECT * FROM nx_direct_referral_settlement WHERE settlement_no='old-paid'");
                assertThat((BigDecimal)old.get("cancelled_usdt")).isEqualByComparingTo("50");
                assertThat((BigDecimal)old.get("cancelled_nex")).isEqualByComparingTo("100");
                assertThat(old.get("credited_usdt_at")).isNotNull();assertThat(old.get("credited_nex_at")).isNotNull();
                results.put("oldBackfill",old);
            }
            jdbc.update("INSERT INTO nx_direct_referral_settlement(settlement_no,source_environment,source_type,source_ref,layer_no,source_user_id,beneficiary_user_id,source_occurred_at,policy_version,policy_snapshot,basis_usdt,amount_usdt,amount_nex,cancelled_usdt,cancelled_nex,status,release_at,credited_at,credited_usdt_at,credited_nex_at,reversal_recorded) VALUES('new-partial','PRODUCTION','network','new-order',2,1,2,NOW(),2,'{}',50,50,100,50,0,'UNLOCKED',NOW(),NOW(),NULL,NOW(),0)");
            var before=jdbc.queryForMap("SELECT * FROM nx_direct_referral_settlement WHERE settlement_no='new-partial'");
            runMysql(evidence,database,"repeat",migration);
            var after=jdbc.queryForMap("SELECT * FROM nx_direct_referral_settlement WHERE settlement_no='new-partial'");
            assertThat(after).isEqualTo(before);
            results.put(mode,Map.of("database",database,"before",before,"after",after,"cutoverRows",0));
        }
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("seven-layer-migrations.json").toFile(),Map.of("port",33335,"results",results));
    }

    private static void runMysql(Path evidence,String database,String phase,String sql) throws Exception {
        String mysql=System.getenv().getOrDefault("DIRECT_REFERRAL_MYSQL","D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/mysql-verified/mysql-8.4.6-winx64/bin/mysql.exe");
        Path input=evidence.resolve(database+"-"+phase+".sql"),log=evidence.resolve(database+"-"+phase+".log");
        Files.writeString(input,sql,StandardCharsets.UTF_8);
        var process=new ProcessBuilder(mysql,"--host=127.0.0.1","--port=33335","--user=root","--default-character-set=utf8mb4","--binary-mode","--database="+("baseline".equals(phase)?"direct_referral_acceptance_20261005":database))
                .redirectInput(input.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(process.waitFor(60,TimeUnit.SECONDS)).as("MySQL migration finishes: %s",log).isTrue();
        assertThat(process.exitValue()).as("MySQL migration output: %s",Files.readString(log)).isZero();
    }
}
