package com.Tkmind.fireflies_proxy.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@ConfigurationProperties(prefix = "fireflies")
@Getter
@Setter
public class FirefliesConfig {

    private Api api = new Api();
    private Webhook webhook = new Webhook();

    @Getter
    @Setter
    public static class Api {
        private String baseUrl = "https://api.fireflies.ai/graphql";

        private String apiKey;

    }

    @Getter
    @Setter
    public static class Webhook {
        private String secret;
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
