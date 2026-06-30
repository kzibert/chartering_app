package com.chartering.clean.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Chartering Clean API",
        version = "0.0.1",
        description = "Vessel-centric API over the chartering_clean database: "
                + "filter vessels, navigate to owner companies and their contacts, "
                + "and manage the 'reached again & confirmed up to date' flags."))
public class OpenApiConfig {
}
