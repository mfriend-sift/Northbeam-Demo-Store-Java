package com.northbeam.store;

import com.northbeam.store.lib.Http;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.Lifecycle;
import org.apache.coyote.Adapter;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.net.SocketEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Two places where Tomcat is stricter than the other stores' servers, before any filter can run:
 * a request path with a bad percent escape (%zz, or bytes that are not UTF-8) is a 400 HTML page, and an encoded
 * slash (%2F) is a 400. The contract wants both read as plain text: the bad escape is a 400 JSON only in a route
 * parameter (Http.checkParams, which reads the path as the client sent it) and an ordinary unknown path elsewhere.
 * So the connector decodes %2F, and a path whose escapes are bad is re-spelled with every % as %25 (read literally,
 * it matches no route); the original path is kept on the request for Http.rawPath.
 */
@Component
public class TomcatTuning implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            connector.setEncodedSolidusHandling("decode");
            connector.addLifecycleListener(event -> {
                if (Lifecycle.AFTER_INIT_EVENT.equals(event.getType())) {
                    ProtocolHandler protocol = connector.getProtocolHandler();
                    protocol.setAdapter(new Lenient(protocol.getAdapter()));
                }
            });
        });
    }

    /** True when every % is followed by two hex digits and the bytes they spell are UTF-8. */
    static boolean wellFormed(String path) {
        if (path.indexOf('%') < 0) return true;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c != '%') { bytes.write(c); continue; } // the path is read byte for byte (ISO-8859-1)
            if (i + 2 >= path.length()) return false;
            int hi = Character.digit(path.charAt(i + 1), 16), lo = Character.digit(path.charAt(i + 2), 16);
            if (hi < 0 || lo < 0) return false;
            bytes.write(hi * 16 + lo);
            i += 2;
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()));
            return true;
        } catch (CharacterCodingException e) { return false; }
    }

    private record Lenient(Adapter next) implements Adapter {
        @Override public void service(Request req, Response res) throws Exception {
            MessageBytes uri = req.requestURI();
            String raw = uri.toString();
            if (raw != null && !wellFormed(raw)) {
                req.setAttribute(Http.RAW_PATH_KEY, raw);
                byte[] fixed = raw.replace("%", "%25").getBytes(StandardCharsets.ISO_8859_1);
                uri.setBytes(fixed, 0, fixed.length);
            }
            next.service(req, res);
        }
        @Override public boolean prepare(Request req, Response res) throws Exception { return next.prepare(req, res); }
        @Override public boolean asyncDispatch(Request req, Response res, SocketEvent status) throws Exception { return next.asyncDispatch(req, res, status); }
        @Override public void log(Request req, Response res, long time) { next.log(req, res, time); }
        @Override public void checkRecycled(Request req, Response res) { next.checkRecycled(req, res); }
        @Override public String getDomain() { return next.getDomain(); }
    }
}
