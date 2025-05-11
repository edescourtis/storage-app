package com.example.storage_app.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

public class GridFsIndexUtil {
    private static final Logger logger = LoggerFactory.getLogger(GridFsIndexUtil.class);

    /**
     * Ensures all required indexes exist on the given GridFS files collection.
     * This method is idempotent and can be safely called multiple times.
     */
    public static void ensureGridFsIndexes(MongoTemplate mongoTemplate, String collectionName) {
        if (!mongoTemplate.collectionExists(collectionName)) {
            logger.info("Collection '{}' does not exist yet. Skipping index creation. It will likely be created by GridFS operations.", collectionName);
            return;
        }
        IndexOperations indexOps = mongoTemplate.indexOps(collectionName);
        logger.info("Ensuring MongoDB indexes for collection: {}", collectionName);

        IndexDefinition systemFilenameIndex = new Index()
                .on("filename", Sort.Direction.ASC)
                .unique();
        indexOps.ensureIndex(systemFilenameIndex);
        logger.info("Ensured index on 'filename' for collection: {}", collectionName);

        IndexDefinition originalFilenameIndex = new Index()
                .on("metadata.ownerId", Sort.Direction.ASC)
                .on("metadata.originalFilename", Sort.Direction.ASC)
                .unique();
        indexOps.ensureIndex(originalFilenameIndex);
        logger.info("Ensured index on 'metadata.ownerId' and 'metadata.originalFilename' for collection: {}", collectionName);

        IndexDefinition contentHashIndex = new Index()
                .on("metadata.ownerId", Sort.Direction.ASC)
                .on("metadata.sha256", Sort.Direction.ASC)
                .unique()
                .sparse(); 
        indexOps.ensureIndex(contentHashIndex);
        logger.info("Ensured sparse unique index on 'metadata.ownerId' and 'metadata.sha256' for collection: {}", collectionName);

        IndexDefinition tokenIndex = new Index()
               .on("metadata.token", Sort.Direction.ASC)
               .unique();
        indexOps.ensureIndex(tokenIndex);
        logger.info("Ensured unique index on 'metadata.token' for collection: {}", collectionName);
        
        logger.info("MongoDB index creation/verification complete for collection: {}", collectionName);
    }
} 