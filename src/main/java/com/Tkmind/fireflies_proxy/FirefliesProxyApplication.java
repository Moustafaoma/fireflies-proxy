package com.Tkmind.fireflies_proxy;

import com.Tkmind.fireflies_proxy.config.FirefliesConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FirefliesConfig.class)
public class FirefliesProxyApplication {
	public static void main(String[] args) {
		SpringApplication.run(FirefliesProxyApplication.class, args);
	}
}

