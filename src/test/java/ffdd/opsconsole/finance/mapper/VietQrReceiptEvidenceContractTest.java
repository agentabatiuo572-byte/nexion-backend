package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class VietQrReceiptEvidenceContractTest {
    @Test
    void migrationStoresDigestAuthorityAndOneTimeBindingState() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts", "migrations", "20260825_vietqr_receipt_evidence.sql"));

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS nx_vietqr_receipt_evidence")
                .contains("asset_id VARCHAR(64) NOT NULL")
                .contains("content_sha256 CHAR(64) NOT NULL")
                .contains("uploaded_by VARCHAR(128) NOT NULL")
                .contains("status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE'")
                .contains("UNIQUE KEY uk_vietqr_receipt_evidence_asset (asset_id)")
                .contains("status IN ('AVAILABLE', 'BOUND')");
    }

    @Test
    void claimOnlyTransitionsAnAvailableUnboundPurposeMatchedAsset() throws Exception {
        String sql = String.join("\n", VietQrReceiptEvidenceMapper.class.getMethod(
                "bindAvailableEvidence", String.class, String.class, String.class, String.class, String.class)
                .getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("SET status = 'BOUND'")
                .contains("purpose = #{purpose}")
                .contains("status = 'AVAILABLE'")
                .contains("bound_resource_id IS NULL")
                .contains("is_deleted = 0");
    }
}
