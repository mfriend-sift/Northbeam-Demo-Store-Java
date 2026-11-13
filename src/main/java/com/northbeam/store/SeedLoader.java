package com.northbeam.store;

import com.northbeam.store.lib.Db;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Startup: the data is loaded from contract/seed.json before the first request, and the port line is printed once it listens. */
@Component
public class SeedLoader {
    @PostConstruct
    public void load() { Db.load(Path.of("").toAbsolutePath()); }

    @EventListener
    public void listening(WebServerInitializedEvent event) {
        System.out.println("Northbeam Goods listening on http://localhost:" + event.getWebServer().getPort());
    }
}
