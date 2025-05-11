package com.example.storage_app.util;

import com.example.storage_app.model.FileRecord;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class GridFsHelper {
  @Autowired private GridFsTemplate gridFsTemplate;
  private static final String HASH_ALGO = "SHA-256";

  public FileStorageResult storeAndHash(MultipartFile file, FileRecord record)
      throws IOException, NoSuchAlgorithmException {
    MimeUtil.Detected detected = MimeUtil.detect(file.getInputStream());
    String mimeType = detected.contentType != null ? detected.contentType : file.getContentType();
    if (mimeType == null || mimeType.isBlank()) {
      mimeType = "application/octet-stream";
    }
    MessageDigest md = MessageDigest.getInstance(HASH_ALGO);
    Document metadata =
        new Document()
            .append("systemFilenameUUID", record.getFilename())
            .append("ownerId", record.getOwnerId())
            .append("originalFilename", record.getOriginalFilename())
            .append("tags", record.getTags())
            .append("visibility", record.getVisibility().name())
            .append("token", record.getToken())
            .append("uploadDate", record.getUploadDate())
            .append("contentType", record.getContentType())
            .append("size", record.getSize());
    ObjectId storedFileObjectId;
    try (InputStream actualStream = detected.stream;
        DigestInputStream digestIn = new DigestInputStream(actualStream, md)) {
      storedFileObjectId = gridFsTemplate.store(digestIn, record.getFilename(), mimeType, metadata);
      if (storedFileObjectId == null) {
        throw new IOException("Failed to store file, GridFS store operation returned null ID.");
      }
    }
    String hash = HexFormat.of().formatHex(md.digest());
    return new FileStorageResult(storedFileObjectId, hash, mimeType, file.getSize(), metadata);
  }
}
