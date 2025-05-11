package com.example.storage_app;

import com.example.storage_app.util.GridFsIndexUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration
public class MongoTestIndexConfiguration {
  @Autowired private MongoTemplate mongoTemplate;

  @PostConstruct
  public void ensureIndexes() {
    GridFsIndexUtil.ensureGridFsIndexes(mongoTemplate, "fs.files");
  }
}
