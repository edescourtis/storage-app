package com.example.storage_app;

import org.springframework.boot.SpringApplication;

public class TestStorageApp {

  public static void main(String[] args) {
    SpringApplication.from(StorageApp::main).with(TestcontainersConfiguration.class).run(args);
  }

}
