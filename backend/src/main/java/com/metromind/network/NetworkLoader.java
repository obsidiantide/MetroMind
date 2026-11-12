package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

@Configuration
public class NetworkLoader {

    @Bean
    public MetroNetwork metroNetwork(ObjectMapper mapper,
                                     @Value("${metromind.network:classpath:network/metropolis.json}") Resource resource)
            throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return mapper.readValue(in, MetroNetwork.class);
        }
    }
}
