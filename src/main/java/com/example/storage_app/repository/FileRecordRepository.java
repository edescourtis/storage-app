package com.example.storage_app.repository;

import com.example.storage_app.model.FileRecord;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileRecordRepository extends MongoRepository<FileRecord, String> {
  boolean existsByOwnerIdAndOriginalFilename(String ownerId, String originalFilename);

  boolean existsByOwnerIdAndSha256(String ownerId, String sha256);

  Optional<FileRecord> findByOwnerIdAndOriginalFilename(String ownerId, String originalFilename);

  Page<FileRecord> findByOwnerId(String ownerId, Pageable pageable);

  Page<FileRecord> findByVisibility(String visibility, Pageable pageable);

  Page<FileRecord> findByVisibilityAndTagsContaining(
      String visibility, String tag, Pageable pageable);
}
