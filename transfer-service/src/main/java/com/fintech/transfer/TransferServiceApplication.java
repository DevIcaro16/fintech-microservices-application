package com.fintech.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;

@SpringBootApplication(exclude = {R2dbcAutoConfiguration.class, R2dbcTransactionManagerAutoConfiguration.class})
public class TransferServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransferServiceApplication.class, args);
    }
}
