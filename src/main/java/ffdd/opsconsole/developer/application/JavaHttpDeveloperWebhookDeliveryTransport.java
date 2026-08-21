package ffdd.opsconsole.developer.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production transport with fixed-resolution egress. The host is resolved and checked once, then the
 * socket is connected to that exact validated address. HTTPS still uses the original host for SNI and
 * endpoint identification, while redirects are never followed.
 */
@Component
public class JavaHttpDeveloperWebhookDeliveryTransport implements DeveloperWebhookDeliveryTransport {
    private final Environment environment;
    @SuppressWarnings("ArchitectureConfigField")
    private final int timeoutMillis;

    public JavaHttpDeveloperWebhookDeliveryTransport(Environment environment) {
        this.environment = environment;
        this.timeoutMillis = (int) Math.min(Integer.MAX_VALUE,
                Math.max(100L, environment.getProperty("nexion.developer.webhooks.timeout-ms", Long.class, 5000L)));
    }

    @Override
    public Response send(String url, Map<String, String> headers, String rawBody) throws Exception {
        URI uri = URI.create(url);
        InetAddress[] addresses = DeveloperWebhookUrlValidator.resolveAndValidate(uri, environment);
        Exception last = null;
        for (InetAddress address : addresses) {
            try {
                return sendToResolvedAddress(uri, address, headers, rawBody);
            } catch (Exception ex) {
                last = ex;
            }
        }
        if (last != null) throw last;
        throw new IOException("DEVELOPER_WEBHOOK_HOST_UNRESOLVED");
    }

    private Response sendToResolvedAddress(URI uri, InetAddress address, Map<String, String> headers,
                                           String rawBody) throws Exception {
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        try (Socket connected = new Socket()) {
            connected.connect(new InetSocketAddress(address, port), timeoutMillis);
            connected.setSoTimeout(timeoutMillis);
            Socket socket = connected;
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket tls = (SSLSocket) factory.createSocket(connected, uri.getHost(), port, false);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!isIpLiteral(uri.getHost())) parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                socket = tls;
            }
            Socket requestSocket = socket;
            try (requestSocket) {
                writeRequest(requestSocket, uri, headers, rawBody);
                return readResponse(requestSocket);
            }
        }
    }

    private void writeRequest(Socket socket, URI uri, Map<String, String> headers, String rawBody) throws IOException {
        byte[] body = rawBody.getBytes(StandardCharsets.UTF_8);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String host = uri.getHost();
        if (uri.getPort() > 0) host += ":" + uri.getPort();
        StringBuilder request = new StringBuilder()
                .append("POST ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n");
        headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        request.append("\r\n");
        var output = socket.getOutputStream();
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private Response readResponse(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String statusLine = reader.readLine();
        if (statusLine == null) throw new IOException("DEVELOPER_WEBHOOK_EMPTY_RESPONSE");
        String[] pieces = statusLine.split(" ", 3);
        if (pieces.length < 2) throw new IOException("DEVELOPER_WEBHOOK_INVALID_RESPONSE");
        int status = Integer.parseInt(pieces[1]);
        String line;
        long contentLength = -1;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0 && "content-length".equalsIgnoreCase(line.substring(0, separator).trim())) {
                try {
                    contentLength = Long.parseLong(line.substring(separator + 1).trim());
                } catch (NumberFormatException ignored) {
                    throw new IOException("DEVELOPER_WEBHOOK_INVALID_CONTENT_LENGTH");
                }
            }
        }
        if ((status >= 100 && status < 200) || status == 204 || status == 304 || contentLength == 0) {
            return new Response(status, "");
        }
        return new Response(status, readBody(reader, contentLength));
    }

    private String readBody(BufferedReader reader, long contentLength) throws IOException {
        char[] buffer = new char[256];
        int requested = contentLength < 0 ? buffer.length : (int) Math.min(buffer.length, contentLength);
        int read = requested == 0 ? 0 : reader.read(buffer, 0, requested);
        return read <= 0 ? "" : new String(buffer, 0, read);
    }

    private boolean isIpLiteral(String host) {
        if (host == null) return false;
        String value = host.replace("[", "").replace("]", "");
        return value.matches("[0-9.]+") || value.contains(":");
    }
}
