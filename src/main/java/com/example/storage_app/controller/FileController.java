package com.example.storage_app.controller;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.controller.dto.PagedResponse;
import com.example.storage_app.service.FileService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

  private final FileService fileService;

  public FileController(FileService fileService) {
    this.fileService = fileService;
  }

  @PostMapping(consumes = "multipart/form-data")
  public ResponseEntity<FileResponse> uploadFile(
      @RequestHeader("X-User-Id") String userId,
      @RequestPart("file") MultipartFile file,
      @Valid @RequestPart("properties") FileUploadRequest request)
      throws IOException, NoSuchAlgorithmException {
    FileResponse responseDto = fileService.uploadFile(userId, file, request);
    try {
      URI location = new URI(responseDto.downloadLink());
      return ResponseEntity.created(location).body(responseDto);
    } catch (URISyntaxException e) {
      throw new RuntimeException(
          "Error creating location URI from download link: " + e.getMessage(), e);
    }
  }

  @GetMapping
  public ResponseEntity<PagedResponse<FileResponse>> listFiles(
      @RequestHeader(name = "X-User-Id", required = false) String userId,
      @RequestParam(required = false) String tag,
      @RequestParam(defaultValue = "uploadDate") String sortBy,
      @RequestParam(defaultValue = "desc") String sortDir,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    Page<FileResponse> responsePage =
        fileService.listFiles(userId, tag, sortBy, sortDir, page, size);
    PagedResponse<FileResponse> dto =
        new PagedResponse<>(
            responsePage.getContent(),
            responsePage.getNumber(),
            responsePage.getSize(),
            responsePage.getTotalPages(),
            responsePage.getTotalElements(),
            responsePage.isLast(),
            responsePage.isFirst(),
            responsePage.getNumberOfElements());
    return ResponseEntity.ok(dto);
  }

  @GetMapping("/download/{token}")
  public ResponseEntity<GridFsResource> downloadFile(@PathVariable String token)
      throws IOException {
    return fileService.downloadFile(token);
  }

  @PatchMapping("/{fileId}")
  public ResponseEntity<FileResponse> updateFileDetails(
      @RequestHeader("X-User-Id") String userId,
      @PathVariable String fileId,
      @Valid @RequestBody FileUpdateRequest request) {
    FileResponse responseDto = fileService.updateFileDetails(userId, fileId, request);
    return ResponseEntity.ok(responseDto);
  }

  @DeleteMapping("/{fileId}")
  public ResponseEntity<Void> deleteFile(
      @RequestHeader("X-User-Id") String userId, @PathVariable String fileId) {
    fileService.deleteFile(userId, fileId);
    return ResponseEntity.noContent().build();
  }
}
