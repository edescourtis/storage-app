package com.example.storage_app.util;

import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.model.FileRecord;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FileMetadataBuilder {
  public FileRecord build(FileUploadRequest request, String userId, MultipartFile file) {
    String systemFilenameUUID = UUID.randomUUID().toString();
    String token = UUID.randomUUID().toString();
    List<String> lowercaseTags =
        (request.tags() == null ? new ArrayList<String>() : request.tags())
            .stream().filter(Objects::nonNull).map(String::toLowerCase).toList();
    String userProvidedFilename = request.filename();
    if (userProvidedFilename == null || userProvidedFilename.isBlank()) {
      userProvidedFilename = file.getOriginalFilename();
    }
    return FileRecord.builder()
        .id(systemFilenameUUID)
        .filename(systemFilenameUUID)
        .uploadDate(new Date())
        .contentType(file.getContentType())
        .size(file.getSize())
        .ownerId(userId)
        .visibility(request.visibility())
        .tags(lowercaseTags)
        .token(token)
        .originalFilename(userProvidedFilename)
        .build();
  }
}
