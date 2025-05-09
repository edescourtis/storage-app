package com.example.storage_app.repo;

import com.example.storage_app.model.FileRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface FileRepository extends MongoRepository<FileRecord,String> {
    Optional<FileRecord> findByToken(String token);
}
