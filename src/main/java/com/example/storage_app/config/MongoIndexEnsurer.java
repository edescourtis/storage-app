package com.example.storage_app.config;

import java.util.stream.StreamSupport;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.stereotype.Component;

@Component
public class MongoIndexEnsurer {
  private final MongoTemplate mongoTemplate;

  public MongoIndexEnsurer(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @EventListener(ContextRefreshedEvent.class)
  public void ensureIndexes() {
    MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext =
        mongoTemplate.getConverter().getMappingContext();
    MongoPersistentEntityIndexResolver resolver =
        new MongoPersistentEntityIndexResolver(mappingContext);
    StreamSupport.stream(mappingContext.getPersistentEntities().spliterator(), false)
        .filter(entity -> entity.getType().isAnnotationPresent(Document.class))
        .forEach(
            entity -> {
              IndexOperations indexOps = mongoTemplate.indexOps(entity.getType());
              resolver.resolveIndexFor(entity.getType()).forEach(indexOps::ensureIndex);
            });
  }
}
