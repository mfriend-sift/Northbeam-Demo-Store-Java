package com.northbeam.store;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

/** Routes match as the other stores' do: any letter case, and no optional trailing slash. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    @SuppressWarnings("deprecation")
    public void configurePathMatch(PathMatchConfigurer configurer) {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        parser.setMatchOptionalTrailingSeparator(false);
        configurer.setPatternParser(parser);
    }
}
