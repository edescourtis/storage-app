package com.example.storage_app.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.example.storage_app.util.GridFsIndexUtil;

import jakarta.annotation.PostConstruct;

@Configuration
public class MongoProdIndexConfiguration {
    private static final String FS_FILES_COLLECTION = "fs.files";

    @Autowired
    private MongoTemplate mongoTemplate;

    @PostConstruct
    public void ensureIndexes() {
        GridFsIndexUtil.ensureGridFsIndexes(mongoTemplate, FS_FILES_COLLECTION);
    }
} 