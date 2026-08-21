package ffdd.opsconsole.developer.application;

@FunctionalInterface
public interface WebhookSecretCodec {
    String decode(String ciphertext) throws Exception;
}
