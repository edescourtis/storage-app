package com.example.storage_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TestcontainersConfiguration.class);

  @Bean
  @ServiceConnection
  MongoDBContainer mongoDbContainer() {
    log.info("Configuring Testcontainers MongoDB with replica set rs0 command using mongo:8.0.");
    MongoDBContainer container =
        new MongoDBContainer(DockerImageName.parse("mongo:8.0"))
            .withCommand("--replSet", "rs0", "--bind_ip_all");
    return container;
  }
}
