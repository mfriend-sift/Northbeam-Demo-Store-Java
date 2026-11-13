package com.northbeam.store.apis;

import com.northbeam.store.lib.Api;
import com.northbeam.store.lib.Config;
import com.northbeam.store.lib.Db;
import com.northbeam.store.lib.JsObject;
import com.northbeam.store.lib.Reply;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The catalog and the store's own description. */
@RestController
public class StoreApi {
    private static final Pattern SHA256 = Pattern.compile("\\A[0-9a-f]{64}\\z");

    @GetMapping("/api/products")
    public ResponseEntity<byte[]> products(HttpServletRequest http) {
        return Api.run(http, req -> Reply.json(200, new ArrayList<>(Db.products)));
    }

    @GetMapping("/api/config")
    public ResponseEntity<byte[]> config(HttpServletRequest http) {
        return Api.run(http, req -> {
            String hash = Config.env("BUILD_HASH");
            String version = Config.env("STORE_VERSION");
            JsObject config = new JsObject()
                .set("backend", "java")
                .set("buildHash", hash != null && SHA256.matcher(hash).matches() ? hash : null)
                .set("version", version == null || version.isEmpty() ? "clean" : version);
            if (Config.demo()) config.set("demoDefaults", Db.demoDefaults);
            return Reply.json(200, config);
        });
    }
}
