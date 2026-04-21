package com.fintech.transfer.config;

import com.fintech.transfer.adapter.in.web.TransferHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.Map;

@Configuration
public class RouterConfig {

    @Bean
    public RouterFunction<ServerResponse> routes(TransferHandler handler) {
        return RouterFunctions.route()
            .GET("/healthz", req -> ServerResponse.ok().bodyValue(Map.of("status", "ok")))
            .POST("/transfers", handler::create)
            .GET("/transfers/{id}", handler::getById)
            .GET("/transfers", handler::getByUser)
            .build();
    }
}
