package com.example.storage_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

import jakarta.annotation.PostConstruct; // For Spring Boot 3.x - choose one

@Configuration
public class MongoTestIndexConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(MongoTestIndexConfiguration.class);

    @Autowired
    private MongoTemplate mongoTemplate;

    @PostConstruct
    public void ensureIndexes() {
        // Ensure this runs after MongoTemplate is fully initialized and connected
        // For GridFS, the main collection for metadata is typically "fs.files"
        ensureGridFsIndexes("fs.files");
    }

    private void ensureGridFsIndexes(String collectionName) {
        IndexOperations indexOps = mongoTemplate.indexOps(collectionName);
        
        logger.info("Ensuring unique indexes on MongoDB collection: {}", collectionName);

        // System Filename (UUID) - This is often the _id for GridFS chunks' files_id, 
        // but for fs.files, the top-level filename is our system UUID.
        // GridFS itself usually ensures uniqueness on its internal _id for fs.files.
        // The 'filename' field in fs.files should be unique as it's our primary system identifier.
        IndexDefinition systemFilenameIndex = new Index()
                .on("filename", Sort.Direction.ASC)
                .unique();
        indexOps.ensureIndex(systemFilenameIndex);
        logger.info("Ensured unique index on 'filename' for collection: {}", collectionName);

        // User-provided Filename per Owner
        IndexDefinition originalFilenameIndex = new Index()
                .on("metadata.ownerId", Sort.Direction.ASC)
                .on("metadata.originalFilename", Sort.Direction.ASC)
                .unique();
        indexOps.ensureIndex(originalFilenameIndex);
        logger.info("Ensured unique index on 'metadata.ownerId' and 'metadata.originalFilename' for collection: {}", collectionName);

        // Content Hash (SHA256) per Owner
        IndexDefinition contentHashIndex = new Index()
                .on("metadata.ownerId", Sort.Direction.ASC)
                .on("metadata.sha256", Sort.Direction.ASC)
                .unique();
        indexOps.ensureIndex(contentHashIndex);
        logger.info("Ensured unique index on 'metadata.ownerId' and 'metadata.sha256' for collection: {}", collectionName);

        // Download Token
        IndexDefinition tokenIndex = new Index()
               .on("metadata.token", Sort.Direction.ASC)
               .unique();
        indexOps.ensureIndex(tokenIndex);
        logger.info("Ensured unique index on 'metadata.token' for collection: {}", collectionName);

        logger.info("Custom index creation/verification complete for collection: {}", collectionName);
    }
} 