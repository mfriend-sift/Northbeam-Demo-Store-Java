package com.northbeam.store.apis;

import com.northbeam.store.lib.Config;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** True when DEMO=1 by the store's one reading of it (Config.demo), not by Spring's own property sources. */
public class DemoOn implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) { return Config.demo(); }
}
