package com.example.storage_app.service;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.exception.FileAlreadyExistsException;
import com.example.storage_app.exception.ResourceNotFoundException;
import com.example.storage_app.exception.UnauthorizedOperationException;
import com.example.storage_app.model.FileRecord;
import com.example.storage_app.repository.FileRecordRepository;
import com.example.storage_app.util.FileMapper;
import com.example.storage_app.util.FileMetadataBuilder;
import com.example.storage_app.util.FileStorageResult;
import com.example.storage_app.util.GridFsHelper;
import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileServiceImpl implements FileService {
  private final GridFsTemplate gridFs;
  private final MongoTemplate mongoTemplate;
  private final FileMetadataBuilder fileMetadataBuilder;
  private final FileMapper fileMapper;
  private final GridFsHelper gridFsHelper;
  private final FileRecordRepository fileRecordRepository;

  public FileServiceImpl(
      GridFsTemplate gridFs,
      MongoTemplate mongoTemplate,
      FileMetadataBuilder fileMetadataBuilder,
      FileMapper fileMapper,
      GridFsHelper gridFsHelper,
      FileRecordRepository fileRecordRepository) {
    this.gridFs = gridFs;
    this.mongoTemplate = mongoTemplate;
    this.fileMetadataBuilder = fileMetadataBuilder;
    this.fileMapper = fileMapper;
    this.gridFsHelper = gridFsHelper;
    this.fileRecordRepository = fileRecordRepository;
  }

  @Override
  @Transactional
  public FileResponse uploadFile(String userId, MultipartFile file, FileUploadRequest request)
      throws NoSuchAlgorithmException, IOException {
    if (fileRecordRepository.existsByOwnerIdAndOriginalFilename(userId, request.filename())) {
      throw new FileAlreadyExistsException(
          "Filename '" + request.filename() + "' already exists for this user.");
    }
    FileRecord record = fileMetadataBuilder.build(request, userId, file);
    FileStorageResult storageResult = gridFsHelper.storeAndHash(file, record);
    record.setSha256(storageResult.sha256);
    fileRecordRepository.save(record);
    return fileMapper.fromEntity(record);
  }

  private String mapSortField(String apiSortField) {
    if (apiSortField == null) {
      return "uploadDate";
    }
    return switch (apiSortField.toLowerCase()) {
      case "filename" -> "metadata.originalFilename";
      case "uploaddate" -> "uploadDate";
      case "contenttype" -> "contentType";
      case "size" -> "length";
      case "tag", "tags" -> "metadata.tags";
      default -> throw new IllegalArgumentException("Invalid sortBy field: " + apiSortField);
    };
  }

  @Override
  public Page<FileResponse> listFiles(
      String userId, String filterTag, String sortBy, String sortDir, int pageNum, int pageSize) {
    Sort.Direction direction =
        (sortDir != null && sortDir.equalsIgnoreCase("desc"))
            ? Sort.Direction.DESC
            : Sort.Direction.ASC;
    String sortField = mapSortField(sortBy);
    Pageable pageable = PageRequest.of(pageNum, pageSize, Sort.by(direction, sortField));
    if (userId != null) {
      return fileRecordRepository.findByOwnerId(userId, pageable).map(fileMapper::fromEntity);
    } else if (filterTag != null && !filterTag.isBlank()) {
      return fileRecordRepository
          .findByVisibilityAndTagsContaining("PUBLIC", filterTag, pageable)
          .map(fileMapper::fromEntity);
    } else {
      return fileRecordRepository.findByVisibility("PUBLIC", pageable).map(fileMapper::fromEntity);
    }
  }

  @Override
  public GridFsResource downloadFile(String token) {
    Query query = Query.query(Criteria.where("metadata.token").is(token));
    GridFSFile gridFSFile = gridFs.findOne(query);

    if (gridFSFile == null) {
      throw new ResourceNotFoundException("File not found for token: " + token);
    }
    return gridFs.getResource(gridFSFile);
  }

  @Override
  public FileResponse updateFileDetails(String userId, String fileId, FileUpdateRequest request) {
    FileRecord record =
        fileRecordRepository
            .findById(fileId)
            .orElseThrow(() -> new ResourceNotFoundException("File not found with id: " + fileId));
    if (!userId.equals(record.getOwnerId())) {
      throw new UnauthorizedOperationException(
          "User '" + userId + "' not authorized to update fileId: " + fileId);
    }
    String newFilename = request.newFilename();
    if (newFilename == null || newFilename.isBlank() || newFilename.equals(record.getFilename())) {
      return fileMapper.fromEntity(record);
    }
    if (fileRecordRepository.existsByOwnerIdAndOriginalFilename(userId, newFilename)) {
      throw new FileAlreadyExistsException(
          "Filename '" + newFilename + "' already exists for this user.");
    }
    record.setFilename(newFilename);
    fileRecordRepository.save(record);
    return fileMapper.fromEntity(record);
  }

  @Override
  public void deleteFile(String userId, String fileId) {
    FileRecord record =
        fileRecordRepository
            .findById(fileId)
            .orElseThrow(() -> new ResourceNotFoundException("File not found with id: " + fileId));
    if (!userId.equals(record.getOwnerId())) {
      throw new UnauthorizedOperationException(
          "User '" + userId + "' not authorized to delete fileId: " + fileId);
    }
    fileRecordRepository.deleteById(fileId);
    gridFs.delete(Query.query(Criteria.where("filename").is(record.getFilename())));
  }
}
