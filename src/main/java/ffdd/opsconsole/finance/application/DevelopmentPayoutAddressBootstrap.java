package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.DevelopmentPayoutAddressMapper;
import ffdd.opsconsole.finance.mapper.DevelopmentPayoutAddressMapper.DevelopmentPayoutAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives the fixed local Passkey account three immediately usable, format-valid
 * payout addresses. These are deterministic development fixtures with no
 * private keys and are consumed only by the development withdrawal simulator.
 * Production never registers this component.
 */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "nexion.finance.development-payout-address.enabled", havingValue = "true")
@Slf4j
public class DevelopmentPayoutAddressBootstrap implements ApplicationRunner {
    private static final String FIXED_COUNTRY_CODE = "+86";
    private static final String FIXED_PHONE = "18708173775";
    private static final List<String> NETWORKS = List.of("USDT-TRC20", "USDT-BEP20", "USDT-ERC20");
    private static final char[] BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private final DevelopmentPayoutAddressMapper mapper;
    @SuppressWarnings("ArchitectureConfigField")
    private final String countryCode;
    @SuppressWarnings("ArchitectureConfigField")
    private final String phone;
    @SuppressWarnings("ArchitectureConfigField")
    private final boolean enabled;

    public DevelopmentPayoutAddressBootstrap(
            DevelopmentPayoutAddressMapper mapper,
            @Value("${nexion.auth.development-passkey-account.country-code:}") String countryCode,
            @Value("${nexion.auth.development-passkey-account.phone:}") String phone,
            @Value("${nexion.finance.development-payout-address.enabled:false}") boolean enabled) {
        this.mapper = mapper;
        this.countryCode = countryCode == null ? "" : countryCode.trim();
        this.phone = phone == null ? "" : phone.trim();
        this.enabled = enabled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        seed();
    }

    @Transactional(rollbackFor = Exception.class)
    public int seed() {
        if (!enabled || !FIXED_COUNTRY_CODE.equals(countryCode) || !FIXED_PHONE.equals(phone)) {
            log.warn("event=DEVELOPMENT_PAYOUT_ADDRESS_SKIPPED reason=configuration_unavailable");
            return 0;
        }
        Long userId = mapper.findDevelopmentUserId(countryCode, phone);
        if (userId == null || userId <= 0) {
            log.warn("event=DEVELOPMENT_PAYOUT_ADDRESS_SKIPPED reason=account_unavailable");
            return 0;
        }
        int inserted = 0;
        for (String network : NETWORKS) {
            DevelopmentPayoutAddress row = new DevelopmentPayoutAddress(
                    userId, network, addressFor(userId, network));
            if (mapper.insertIfAbsent(row) == 1) {
                if (mapper.insertHistory(row) != 1) {
                    throw new IllegalStateException("DEVELOPMENT_PAYOUT_ADDRESS_HISTORY_FAILED");
                }
                inserted++;
            }
        }
        log.info("event=DEVELOPMENT_PAYOUT_ADDRESS_READY inserted={}", inserted);
        return inserted;
    }

    private String addressFor(Long userId, String network) {
        byte[] digest = sha256("nexion-development-payout|" + userId + "|" + network);
        if ("USDT-TRC20".equals(network)) {
            StringBuilder address = new StringBuilder("T");
            for (int index = 0; index < 33; index++) {
                int value = Byte.toUnsignedInt(digest[index % digest.length]);
                address.append(BASE58[value % BASE58.length]);
            }
            return address.toString();
        }
        return "0x" + HexFormat.of().formatHex(digest).substring(0, 40);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", ex);
        }
    }
}
