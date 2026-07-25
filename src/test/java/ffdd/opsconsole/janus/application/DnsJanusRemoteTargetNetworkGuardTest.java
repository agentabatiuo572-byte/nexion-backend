package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class DnsJanusRemoteTargetNetworkGuardTest {
    @Test
    void permitsOnlyWhenEveryResolvedAddressIsPublic() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        assertThat(new DnsJanusRemoteTargetNetworkGuard(host -> new InetAddress[]{publicAddress})
                .allows(URI.create("https://approved.example/path"))).isTrue();
        assertThat(new DnsJanusRemoteTargetNetworkGuard(host -> new InetAddress[]{publicAddress, privateAddress})
                .allows(URI.create("https://approved.example/path"))).isFalse();
    }

    @Test
    void rejectsLoopbackLinkLocalUlaEmptyAndLookupFailure() throws Exception {
        byte[] ula = new byte[16];
        ula[0] = (byte) 0xfc;
        InetAddress[] unsafe = {
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
                InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, 1, 1}),
                InetAddress.getByAddress(ula)
        };
        URI target = URI.create("https://approved.example/path");
        for (InetAddress address : unsafe) {
            assertThat(new DnsJanusRemoteTargetNetworkGuard(host -> new InetAddress[]{address})
                    .allows(target)).isFalse();
        }
        assertThat(new DnsJanusRemoteTargetNetworkGuard(host -> new InetAddress[0]).allows(target)).isFalse();
        assertThat(new DnsJanusRemoteTargetNetworkGuard(host -> {
            throw new UnknownHostException(host);
        }).allows(target)).isFalse();
    }
}
