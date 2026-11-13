package com.northbeam.store.apis;

import com.northbeam.store.lib.Reply;
import com.northbeam.store.lib.Statics;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/**
 * Everything no route claimed: the storefront's files from ./public (GET and HEAD only; a folder answers with its
 * index.html), else the JSON 404. Every /demo/* path without DEMO=1 lands here too.
 */
@RestController
public class FallbackApi {
    private static final Path PUBLIC = Path.of("public").toAbsolutePath().normalize();

    @RequestMapping("/**")
    public ResponseEntity<byte[]> fallback(HttpServletRequest req) throws IOException {
        String method = req.getMethod();
        if (method.equals("GET") || method.equals("HEAD")) {
            Path file = resolve(req.getRequestURI());
            if (file != null) {
                byte[] bytes = Files.readAllBytes(file);
                String type = Statics.contentType(file.getFileName().toString());
                ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=0")
                    .header(HttpHeaders.LAST_MODIFIED, DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneOffset.UTC)))
                    .header(HttpHeaders.CONTENT_TYPE, type == null ? "application/octet-stream" : type)
                    .contentLength(bytes.length);
                return ok.body(bytes);
            }
        }
        return Reply.error(404, "Not found").entity();
    }

    // The file a request path names under public/, or null (not a file, or outside public/).
    private static Path resolve(String rawPath) {
        String path = rawPath;
        int semi = path.indexOf(';');
        if (semi >= 0) path = path.substring(0, semi);
        try { path = UriUtils.decode(path, StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { return null; }
        if (path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) return null;
        if (path.endsWith("/")) path = path + "index.html";
        Path file = PUBLIC.resolve(path.substring(1)).normalize();
        if (!file.startsWith(PUBLIC) || !Files.isRegularFile(file)) return null;
        return file;
    }
}
