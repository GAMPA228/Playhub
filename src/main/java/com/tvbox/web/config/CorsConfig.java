package com.tvbox.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Credential writes require X-Mujian-Settings, which cross-origin preflights may not request.
        // Keep this specific rule before the legacy permissive API mapping.
        registry.addMapping("/api/bilibili/auth")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type");
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
