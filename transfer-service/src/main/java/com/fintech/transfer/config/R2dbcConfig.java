package com.fintech.transfer.config;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;

import java.net.URI;

@Configuration
public class R2dbcConfig {

    @Value("${spring.r2dbc.url}")
    private String r2dbcUrl;

    @Bean("connectionFactory")
    public ConnectionFactory connectionFactory() {
        URI uri = URI.create(r2dbcUrl.replace("r2dbc:", ""));
        String[] userInfo = uri.getUserInfo().split(":");
        return new PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(uri.getHost())
                .port(uri.getPort())
                .database(uri.getPath().substring(1))
                .username(userInfo[0])
                .password(userInfo[1])
                .build()
        );
    }

    @Bean("r2dbcTemplate")
    public R2dbcEntityTemplate r2dbcTemplate() {
        return new R2dbcEntityTemplate(connectionFactory());
    }
}
