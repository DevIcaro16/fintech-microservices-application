package com.fintech.account.config;

import com.fintech.account.adapter.in.web.AccountHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.Map;

@Configuration
public class RouterConfig {

    @Bean
    public RouterFunction<ServerResponse> routes(AccountHandler handler) {
        return RouterFunctions.route()
            .GET("/healthz", req -> ServerResponse.ok().bodyValue(Map.of("status", "ok")))
            .POST("/accounts", handler::createAccount)
            .GET("/accounts/{id}", handler::getAccount)
            .GET("/accounts/{id}/balance", handler::getBalance)
            .POST("/accounts/{id}/debit", handler::debit)
            .POST("/accounts/{id}/credit", handler::credit)
            .build();
    }
}
