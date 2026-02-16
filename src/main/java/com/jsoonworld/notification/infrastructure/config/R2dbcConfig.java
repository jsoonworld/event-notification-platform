package com.jsoonworld.notification.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(basePackages = "com.jsoonworld.notification.infrastructure.persistence.repository")
@EnableR2dbcAuditing
public class R2dbcConfig {
}
