package ffdd.opsconsole.device.application;

/** Binary output contract for the explicitly TEST-only BGE execution channel. */
final class TestCloudBgeArtifact {
    static byte[] verify(String encoded, String hash, double[][] independentlyComputed) {
        try {
            byte[] bytes = decode(encoded,hash);
            if (independentlyComputed == null || independentlyComputed.length != 3) throw new IllegalArgumentException();
            var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (double[] expected : independentlyComputed) {
                if (expected == null || expected.length != 1024) throw new IllegalArgumentException();
                boolean nonzero = false, expectedNonzero = false;
                for (double value : expected) {
                    float actual = buffer.getFloat();
                    if (!Double.isFinite(value) || !Float.isFinite(actual)
                            || Math.abs(actual - value) > 1e-5 + 1e-4 * Math.abs(value)) throw new IllegalArgumentException();
                    nonzero |= actual != 0;
                    expectedNonzero |= value != 0;
                }
                if (!nonzero || !expectedNonzero) throw new IllegalArgumentException();
            }
            return bytes;
        } catch (IllegalArgumentException invalid) {
            throw new ffdd.opsconsole.shared.exception.BizException(422, "TEST_CLOUD_RESULT_INVALID");
        }
    }

    static byte[] decode(String encoded,String hash) {
        try {
            if (encoded == null || encoded.length() != 16384 || hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
            byte[] bytes=java.util.Base64.getDecoder().decode(encoded);
            if(bytes.length!=3*1024*4 || !java.util.Base64.getEncoder().encodeToString(bytes).equals(encoded)
                    || !TestCloudExecutorService.sha256(bytes).equals(hash)) throw new IllegalArgumentException();
            var buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(int row=0;row<3;row++) {
                boolean nonzero=false;
                for(int index=0;index<1024;index++) {
                    float value=buffer.getFloat(); if(!Float.isFinite(value)) throw new IllegalArgumentException(); nonzero|=value!=0;
                }
                if(!nonzero) throw new IllegalArgumentException();
            }
            return bytes;
        } catch(IllegalArgumentException invalid) { throw new ffdd.opsconsole.shared.exception.BizException(422,"TEST_CLOUD_RESULT_INVALID"); }
    }
}
