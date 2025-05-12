package com.example.storage_app;

import lombok.Generated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

@Generated
@SpringBootApplication
@EnableSpringDataWebSupport(
    pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class StorageAppApplication {
  public static void main(String[] args) {
    SpringApplication.run(StorageAppApplication.class, args);
  }
}
