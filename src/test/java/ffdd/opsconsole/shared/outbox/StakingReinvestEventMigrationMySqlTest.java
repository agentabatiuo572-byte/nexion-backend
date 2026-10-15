package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Run only through the existing isolated MySQL fixture; no business rows are copied. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
class StakingReinvestEventMigrationMySqlTest {
    static final String MIGRATION="scripts/migrations/20261016_g1_g7_event_property_revision_forward.sql";
    private CanonicalEventSchemaMySqlFixture db;

    @BeforeEach void setup() throws Exception {
        db=new CanonicalEventSchemaMySqlFixture();
        for(var c:StakingReinvestEventContractTest.contracts()) {
            db.jdbc().update("""
                INSERT INTO nx_event_schema_registry(event_name,owner_domain,family_key,producer,consumers,
                  is_server_authoritative,sampling_policy,current_revision,status,created_by,reason,is_deleted)
                VALUES(?,'market','staking','server','fixture',1,'100%',?,'ACTIVE','fixture','observed revision split',0)
                """,c.event(),c.revision());
            long id=db.jdbc().queryForObject("SELECT id FROM nx_event_schema_registry WHERE event_name=?",Long.class,c.event());
            for(var p:c.complete()) {
                int revision=c.original().contains(p)?c.originalRevision():c.revision();
                db.jdbc().update("""
                    INSERT INTO nx_event_schema_property(schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
                    VALUES(?,?,?,0,?,?,0)
                    """,id,p.propertyName(),p.propertyType(),p.requiredField(),revision);
            }
            db.jdbc().update("INSERT INTO nx_admin_event_lifecycle(event_name,lifecycle_state,version,is_deleted) VALUES(?,'full',1,0)",c.event());
        }
    }
    @AfterEach void cleanup() throws Exception { if(db!=null)db.close(); }
    private void migrate() throws Exception {
        try(Connection c=db.jdbc().getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c,new FileSystemResource(MIGRATION));
        }
    }
    private String publish(StakingReinvestEventContractTest.Contract c) {
        return db.outbox().publishUserEvent("STAKING_POSITION","FIXTURE",c.event(),42L,"P1",1,"2026-W42",c.payload());
    }

    @Test void exactForwardSqlRepairsBothRealMapperContractsAndIsIdempotent() throws Exception {
        for(var c:StakingReinvestEventContractTest.contracts()) {
            assertThat(db.mapper(ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.class).listActiveProperties(c.event())).hasSize(2);
            assertThatThrownBy(()->publish(c)).hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        }
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox",Integer.class)).isZero();
        migrate();
        var after=db.jdbc().queryForList("SELECT * FROM nx_event_schema_property ORDER BY id");
        migrate();
        assertThat(db.jdbc().queryForList("SELECT * FROM nx_event_schema_property ORDER BY id")).isEqualTo(after);
        for(var c:StakingReinvestEventContractTest.contracts()) {
            var actual=db.mapper(ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.class).listActiveProperties(c.event());
            assertThat(actual).containsExactlyInAnyOrderElementsOf(c.complete());
            assertThat(publish(c)).isNotBlank();
        }
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox",Integer.class)).isEqualTo(2);
    }

    @Test void futureGovernanceAndDeletedOrChangedFieldsAreNotReactivated() throws Exception {
        db.jdbc().update("UPDATE nx_event_schema_registry SET current_revision=20261017 WHERE event_name='wallet.reinvest'");
        db.jdbc().update("UPDATE nx_event_schema_property SET is_deleted=1 WHERE property_name='position_no'");
        db.jdbc().update("UPDATE nx_event_schema_property SET property_type='json' WHERE property_name='tier_key'");
        db.jdbc().update("UPDATE nx_event_schema_property SET required_field=0 WHERE property_name='product_code'");
        var protectedRows=db.jdbc().queryForList("""
            SELECT p.* FROM nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id
            WHERE s.event_name='wallet.reinvest' OR p.property_name IN ('position_no','tier_key','product_code') ORDER BY p.id
            """);
        migrate();
        assertThat(db.jdbc().queryForList("""
            SELECT p.* FROM nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id
            WHERE s.event_name='wallet.reinvest' OR p.property_name IN ('position_no','tier_key','product_code') ORDER BY p.id
            """)).isEqualTo(protectedRows);
        assertThat(db.jdbc().queryForList("SELECT lifecycle_state FROM nx_admin_event_lifecycle",String.class)).containsOnly("full");
    }
}
