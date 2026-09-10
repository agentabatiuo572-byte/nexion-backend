package ffdd.opsconsole.platform.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class EventGovernanceMapperSqlContractTest {

    @Test
    void schemaExtensionPromotesOnlyActivePriorRevisionPropertiesWithoutChangingTheirMetadata()
            throws NoSuchMethodException {
        Update update = EventGovernanceMapper.class
                .getMethod("carryForwardProperties", long.class, int.class, int.class)
                .getAnnotation(Update.class);

        assertThat(update).isNotNull();
        String sql = String.join(" ", update.value());
        assertThat(sql)
                .contains("SET registry_revision=#{nextRevision}")
                .contains("schema_id=#{schemaId}")
                .contains("registry_revision=#{currentRevision}")
                .contains("is_deleted=0")
                .doesNotContain("property_type=")
                .doesNotContain("pii=")
                .doesNotContain("required_field=");
    }

    @Test
    void carryForwardCountExcludesDeletedAndNonCurrentPropertyRows() throws NoSuchMethodException {
        Select select = EventGovernanceMapper.class
                .getMethod("countActiveProperties", long.class, int.class)
                .getAnnotation(Select.class);

        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value());
        assertThat(sql)
                .contains("schema_id=#{schemaId}")
                .contains("registry_revision=#{revision}")
                .contains("is_deleted=0");
    }

    @Test
    void livePropertyCountDetectsRevisionDriftWithoutTreatingZeroFieldsAsInvalid() throws NoSuchMethodException {
        Select select = EventGovernanceMapper.class
                .getMethod("countLiveProperties", long.class)
                .getAnnotation(Select.class);

        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value());
        assertThat(sql)
                .contains("schema_id=#{schemaId}")
                .contains("is_deleted=0")
                .doesNotContain("registry_revision");
    }

    @Test
    void exactSchemaPreviewUsesBoundEventNameAndReturnsNoPayloadColumns() throws NoSuchMethodException {
        Select select = EventGovernanceMapper.class
                .getMethod("findSchemaRegistration", String.class)
                .getAnnotation(Select.class);

        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value());
        assertThat(sql)
                .contains("LEFT JOIN nx_event_schema_property p")
                .contains("s.event_name=#{eventName}")
                .contains("p.is_deleted=0")
                .contains("p.registry_revision=s.current_revision")
                .contains("s.status='ACTIVE'")
                .doesNotContain("payload")
                .doesNotContain("LIMIT #{limit}");
    }
}
