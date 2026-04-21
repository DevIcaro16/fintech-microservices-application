package com.fintech.account.config;

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

    @Value("${spring.r2dbc.shard0.url}")
    private String shard0Url;

    @Value("${spring.r2dbc.shard1.url}")
    private String shard1Url;

    @Bean("connectionFactory0")
    public ConnectionFactory connectionFactory0() {
        return buildFactory(shard0Url);
    }

    @Bean("connectionFactory1")
    public ConnectionFactory connectionFactory1() {
        return buildFactory(shard1Url);
    }

    @Bean("r2dbcTemplate0")
    public R2dbcEntityTemplate r2dbcTemplate0() {
        return new R2dbcEntityTemplate(connectionFactory0());
    }

    @Bean("r2dbcTemplate1")
    public R2dbcEntityTemplate r2dbcTemplate1() {
        return new R2dbcEntityTemplate(connectionFactory1());
    }

    private ConnectionFactory buildFactory(String r2dbcUrl) {
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
}
