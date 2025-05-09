package com.example.storage_app;

import org.springframework.boot.SpringApplication;

public class TestStorageAppApplication {

	public static void main(String[] args) {
		SpringApplication.from(StorageAppApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
