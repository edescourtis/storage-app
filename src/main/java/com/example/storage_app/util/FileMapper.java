package com.example.storage_app.util;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.model.FileRecord;
import com.example.storage_app.model.Visibility;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.stereotype.Component;

@Component
public class FileMapper {
  public FileResponse toResponse(FileStorageResult result) {
    Document metadata = result.metadata;
    return new FileResponse(
        metadata.getString("systemFilenameUUID"),
        metadata.getString("originalFilename"),
        Visibility.valueOf(metadata.getString("visibility")),
        (List<String>) metadata.get("tags"),
        metadata.getDate("uploadDate"),
        result.contentType,
        result.size,
        "/api/v1/files/download/" + metadata.getString("token"));
  }

  public FileResponse fromDocument(Document fsFileDoc) {
    Document metadata = fsFileDoc.get("metadata", Document.class);
    String systemFileId = fsFileDoc.getString("filename");
    if (metadata == null) {
      throw new RuntimeException("File metadata is missing for system ID: " + systemFileId);
    }
    String originalFilename = metadata.getString("originalFilename");
    String visString = metadata.getString("visibility");
    Visibility visibilityEnum = Visibility.PRIVATE;
    if (visString != null) {
      try {
        visibilityEnum = Visibility.valueOf(visString.toUpperCase());
      } catch (IllegalArgumentException ignored) {
      }
    }
    List<String> tags = metadata.getList("tags", String.class, java.util.Collections.emptyList());
    Date uploadDate = fsFileDoc.getDate("uploadDate");
    String contentType = metadata.getString("contentType");
    long size = metadata.getLong("size") != null ? metadata.getLong("size") : 0L;
    String downloadLink = "/api/v1/files/download/" + metadata.getString("token");
    return new FileResponse(
        systemFileId,
        originalFilename,
        visibilityEnum,
        tags,
        uploadDate,
        contentType,
        size,
        downloadLink);
  }

  public FileResponse fromEntity(FileRecord fileRecord) {
    if (fileRecord == null) return null;
    return new FileResponse(
        fileRecord.getId(),
        fileRecord.getOriginalFilename(),
        fileRecord.getVisibility(),
        fileRecord.getTags(),
        fileRecord.getUploadDate(),
        fileRecord.getContentType(),
        fileRecord.getSize(),
        "/api/v1/files/download/" + fileRecord.getToken());
  }
}
