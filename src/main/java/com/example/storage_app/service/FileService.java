package com.example.storage_app.service;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

  FileResponse uploadFile(String userId, MultipartFile file, FileUploadRequest request)
      throws IOException, NoSuchAlgorithmException;

  Page<FileResponse> listFiles(
      String userId, String tag, String sortBy, String sortDir, int page, int size);

  GridFsResource downloadFile(String token);

  FileResponse updateFileDetails(String userId, String fileId, FileUpdateRequest request);

  void deleteFile(String userId, String fileId);
}
