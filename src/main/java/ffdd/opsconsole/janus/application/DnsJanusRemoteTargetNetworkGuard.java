package ffdd.opsconsole.janus.application;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.util.StringUtils;

public class DnsJanusRemoteTargetNetworkGuard implements JanusRemoteTargetNetworkGuard {
    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final AddressResolver resolver;

    public DnsJanusRemoteTargetNetworkGuard() {
        this(InetAddress::getAllByName);
    }

    DnsJanusRemoteTargetNetworkGuard(AddressResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean allows(URI uri) {
        if (uri == null || !StringUtils.hasText(uri.getHost())) return false;
        try {
            InetAddress[] addresses = resolver.resolve(uri.getHost());
            if (addresses == null || addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (address == null || unsafe(address)) return false;
            }
            return true;
        } catch (UnknownHostException | SecurityException ex) {
            return false;
        }
    }

    private boolean unsafe(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return (first & 0xfe) == 0xfc;
        }
        if (bytes.length != 4) return true;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 0 || first == 127
                || first == 10
                || first == 100 && second >= 64 && second <= 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168;
    }
}
