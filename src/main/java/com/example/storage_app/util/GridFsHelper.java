package com.example.storage_app.util;

import com.example.storage_app.exception.FileAlreadyExistsException;
import com.example.storage_app.model.FileRecord;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class GridFsHelper {
  @Autowired private GridFsTemplate gridFsTemplate;
  @Autowired private MongoTemplate mongoTemplate;
  private static final String HASH_ALGO = "SHA-256";
  private static final Logger log = LoggerFactory.getLogger(GridFsHelper.class);

  public FileStorageResult storeAndHash(MultipartFile file, FileRecord record)
      throws IOException, NoSuchAlgorithmException {
    MimeUtil.Detected detected = MimeUtil.detect(file.getInputStream());
    InputStream actualStream = detected.stream;

    String effectiveMimeType = detected.contentType;
    if (effectiveMimeType == null || effectiveMimeType.isBlank()) {
      effectiveMimeType = file.getContentType();
    }
    if (effectiveMimeType == null || effectiveMimeType.isBlank()) {
      effectiveMimeType = "application/octet-stream";
    }

    MessageDigest md = MessageDigest.getInstance(HASH_ALGO);

    Document gridFsMetadata =
        new Document()
            .append("systemFilenameUUID", record.getFilename())
            .append("ownerId", record.getOwnerId())
            .append("originalFilename", record.getOriginalFilename())
            .append("tags", record.getTags())
            .append("visibility", record.getVisibility().name())
            .append("token", record.getToken())
            .append("uploadDate", record.getUploadDate())
            .append("contentType", effectiveMimeType)
            .append("size", record.getSize());

    ObjectId storedFileObjectId;
    String hash = null;
    try (DigestInputStream digestIn = new DigestInputStream(actualStream, md)) {
      storedFileObjectId =
          gridFsTemplate.store(digestIn, record.getFilename(), effectiveMimeType, gridFsMetadata);
      hash = HexFormat.of().formatHex(digestIn.getMessageDigest().digest());
      if (storedFileObjectId == null) {
        throw new IOException("Failed to store file, GridFS store operation returned null ID.");
      }
    }

    Query query = Query.query(Criteria.where("_id").is(storedFileObjectId));
    Update update = new Update().set("metadata.sha256", hash);
    try {
      mongoTemplate.updateFirst(query, update, "fs.files");
      log.info(
          "Successfully updated metadata.sha256 for fileId {} with hash {}",
          storedFileObjectId,
          hash);
    } catch (DuplicateKeyException e) {
      log.warn(
          "Duplicate content (sha256: {}) detected for owner {} during metadata update for fileId {}. Deleting orphaned GridFS file.",
          hash,
          record.getOwnerId(),
          storedFileObjectId);
      gridFsTemplate.delete(query);
      throw new FileAlreadyExistsException(
          "Content with hash '" + hash + "' already exists for this user.");
    }

    return new FileStorageResult(
        storedFileObjectId, hash, effectiveMimeType, file.getSize(), gridFsMetadata);
  }
}
