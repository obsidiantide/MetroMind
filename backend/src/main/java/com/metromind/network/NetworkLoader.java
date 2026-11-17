package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Loads the network named by {@code metromind.network}: a GeoJSON feature collection when the file ends in
 * {@code .geojson}, otherwise MetroMind's own JSON format.
 */
@Configuration
public class NetworkLoader {

    @Bean
    public MetroNetwork metroNetwork(ObjectMapper mapper,
                                     @Value("${metromind.network:classpath:network/metropolis.json}") Resource resource)
            throws IOException {
        return load(mapper, resource);
    }

    static MetroNetwork load(ObjectMapper mapper, Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            if (isGeoJson(resource)) {
                return new GeoJsonNetworkReader(mapper).read(in);
            }
            return mapper.readValue(in, MetroNetwork.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid network " + resource.getDescription() + ": " + e.getMessage(), e);
        }
    }

    static boolean isGeoJson(Resource resource) {
        String name = resource.getFilename();
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".geojson");
    }
}
