package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CregisExceptionSchemaGuardTest {
    record Metadata(List<Map<String,Object>> columns,List<Map<String,Object>> indexes) {}
    Metadata actualSchema() throws Exception {
        return new ObjectMapper().readValue(Files.readString(Path.of("src/test/resources/cregis/exception-schema-erc20.json")),Metadata.class);
    }
    @Test void migratedTestMetadataHasTheExactNetworkScopedSourcePrimaryKey() throws Exception {
        var metadata=actualSchema();
        assertThatCode(()->CregisExceptionSchemaGuard.validate(metadata.columns(),metadata.indexes())).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"missing-chain","legacy-primary","wrong-order","non-unique","unique-without-primary","prefix","legacy-extra-unique","money-scale","other-required-column","other-unique"})
    void damagedSchemaCannotPassThePublicationGuard(String defect) throws Exception {
        var metadata=actualSchema();
        var primary=metadata.indexes().stream().filter(row->"nx_cregis_exception_source".equals(row.get("tableName"))&&"PRIMARY".equals(row.get("indexName"))).findFirst().orElseThrow();
        switch(defect) {
            case "missing-chain" -> metadata.columns().removeIf(row->"nx_cregis_exception_source".equals(row.get("tableName"))&&"chain_id".equals(row.get("columnName")));
            case "legacy-primary" -> primary.put("indexColumns","project_id,txid,log_index");
            case "wrong-order" -> primary.put("indexColumns","chain_id,project_id,txid,log_index");
            // The mapper returns only unique indexes, so an ordinary replacement does not appear at all.
            case "non-unique" -> metadata.indexes().remove(primary);
            case "unique-without-primary" -> primary.put("indexName","source_log");
            case "prefix" -> primary.put("indexColumns","project_id,chain_id,txid(8),log_index");
            case "legacy-extra-unique" -> metadata.indexes().add(Map.of("tableName","nx_cregis_exception_source","indexName","old_source_log","indexColumns","project_id,txid,log_index"));
            case "money-scale" -> metadata.columns().stream().filter(row->"nx_cregis_exception_source".equals(row.get("tableName"))&&"gross_amount".equals(row.get("columnName"))).findFirst().orElseThrow().put("numericScale",5);
            case "other-required-column" -> metadata.columns().removeIf(row->"nx_cregis_exception_source".equals(row.get("tableName"))&&"alias_of_log_index".equals(row.get("columnName")));
            case "other-unique" -> metadata.indexes().removeIf(row->"nx_cregis_exception_refund".equals(row.get("tableName"))&&"business_key".equals(row.get("indexColumns")));
            default -> throw new IllegalArgumentException(defect);
        }
        assertThatThrownBy(()->CregisExceptionSchemaGuard.validate(metadata.columns(),metadata.indexes()))
                .hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
    }
}
