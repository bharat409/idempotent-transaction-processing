package com.example.fde_transaction_service.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TransactionProcessingConfiguration {
	@Bean(name = "transactionProcessingExecutor", destroyMethod = "shutdown")
	public ExecutorService transactionProcessingExecutor() {
		return Executors.newFixedThreadPool(4);
	}
}