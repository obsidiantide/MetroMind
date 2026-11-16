package com.metromind.infra;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of the REST API, served by springdoc at {@code /api-docs} with Swagger UI at
 * {@code /swagger-ui.html}. Only {@code /api/**} is documented; actuator and WebSocket endpoints are left out.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI metroMindOpenApi() {
        return new OpenAPI().info(new Info()
                .title("MetroMind API")
                .version("0.1.0")
                .description("""
                        Digital twin of a metro system: read the live simulation state, inject incidents, run \
                        what-if delay-propagation analyses and benchmark headway strategies. Live snapshots are \
                        also pushed over STOMP at /ws, topic /topic/state."""));
    }
}
