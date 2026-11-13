package com.northbeam.store.apis;

import com.northbeam.store.lib.Http;
import com.northbeam.store.lib.Reply;
import com.northbeam.store.lib.Statics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/**
 * Everything no route claimed: the storefront's files from ./public (GET and HEAD only; a folder answers with its
 * index.html; one byte range is honored), else the JSON 404. Every /demo/* path without DEMO=1 lands here too.
 */
@RestController
public class FallbackApi {
    private static final Path PUBLIC = Path.of("public").toAbsolutePath().normalize();
    private static final Pattern RANGE = Pattern.compile("\\Abytes=(\\d*)-(\\d*)\\z");

    @RequestMapping("/**")
    public void fallback(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String method = req.getMethod();
        Path file = method.equals("GET") || method.equals("HEAD") ? resolve(Http.rawPath(req)) : null;
        if (file == null) { Reply.error(404, "Not found").writeTo(res); return; }
        byte[] bytes = Files.readAllBytes(file);
        long modified = Files.getLastModifiedTime(file).toMillis() / 1000 * 1000;
        String etag = "\"" + Long.toHexString(modified) + Long.toHexString(bytes.length) + "\"";
        String type = Statics.contentType(file.getFileName().toString());
        res.setHeader("Cache-Control", "public, max-age=0");
        res.setHeader("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(modified), ZoneOffset.UTC)));
        res.setHeader("ETag", etag);
        res.setHeader("Accept-Ranges", "bytes");
        String none = req.getHeader("If-None-Match");
        if (none != null && java.util.Arrays.stream(none.split(",")).anyMatch(t -> t.trim().equals(etag) || t.trim().equals("*"))) { res.setStatus(304); return; }
        res.setContentType(type == null ? "application/octet-stream" : type);
        long start = 0, end = bytes.length - 1;
        String range = req.getHeader("Range");
        Matcher m = range == null ? null : RANGE.matcher(range.trim());
        if (m != null && m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
            try {
                if (m.group(1).isEmpty()) { long n = Long.parseLong(m.group(2)); start = Math.max(0, bytes.length - n); }
                else { start = Long.parseLong(m.group(1)); if (!m.group(2).isEmpty()) end = Math.min(end, Long.parseLong(m.group(2))); }
            } catch (NumberFormatException e) { start = bytes.length; }
            if (start >= bytes.length || start > end) {
                res.setStatus(416);
                res.setHeader("Content-Range", "bytes */" + bytes.length);
                res.setContentLength(0);
                return;
            }
            res.setStatus(206);
            res.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
        }
        res.setContentLength((int) (end - start + 1));
        res.getOutputStream().write(bytes, (int) start, (int) (end - start + 1));
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
        for (Path part : PUBLIC.relativize(file)) if (part.toString().startsWith(".")) return null; // a dotfile or a dot folder is never served
        return file;
    }
}
