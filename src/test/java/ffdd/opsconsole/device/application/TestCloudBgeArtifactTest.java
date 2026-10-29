package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class TestCloudBgeArtifactTest {
    static byte[] artifact(float value) {
        ByteBuffer buffer = ByteBuffer.allocate(3 * 1024 * 4).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) buffer.putFloat(value);
        return buffer.array();
    }
    static double[][] expected(double value) {
        double[][] result = new double[3][1024];
        for (double[] row : result) java.util.Arrays.fill(row, value);
        return result;
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @Test void correctHashOfWrongSemanticOutputIsRejected() throws Exception {
        byte[] wrong = artifact(0.5f);
        assertThatThrownBy(() -> TestCloudBgeArtifact.verify(Base64.getEncoder().encodeToString(wrong), hash(wrong), expected(0.25)))
                .hasMessage("TEST_CLOUD_RESULT_INVALID");
    }
    @Test void actualFloat32LittleEndianOutputIsAccepted() throws Exception {
        byte[] bytes = artifact(0.25f);
        assertThat(TestCloudBgeArtifact.verify(Base64.getEncoder().encodeToString(bytes), hash(bytes), expected(0.25)))
                .containsExactly(bytes);
    }
    @Test void tinyNonzeroArtifactCannotValidateAnAllZeroIndependentModelResult() throws Exception {
        byte[] bytes=artifact(0.000001f);
        assertThatThrownBy(()->TestCloudBgeArtifact.verify(Base64.getEncoder().encodeToString(bytes),hash(bytes),expected(0)))
                .hasMessage("TEST_CLOUD_RESULT_INVALID");
    }
    @Test void malformedNonFiniteZeroAndWrongHashAreRejected() throws Exception {
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0f}) {
            byte[] bytes = artifact(value);
            assertThatThrownBy(() -> TestCloudBgeArtifact.verify(Base64.getEncoder().encodeToString(bytes), hash(bytes), expected(value)))
                    .hasMessage("TEST_CLOUD_RESULT_INVALID");
        }
        byte[] bytes = artifact(0.25f);
        assertThatThrownBy(() -> TestCloudBgeArtifact.verify(Base64.getEncoder().encodeToString(bytes), "a".repeat(64), expected(0.25)))
                .hasMessage("TEST_CLOUD_RESULT_INVALID");
    }
}
