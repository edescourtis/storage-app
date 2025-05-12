package com.example.storage_app.service;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.exception.FileAlreadyExistsException;
import com.example.storage_app.exception.InvalidRequestArgumentException;
import com.example.storage_app.exception.ResourceNotFoundException;
import com.example.storage_app.exception.StorageException;
import com.example.storage_app.exception.UnauthorizedOperationException;
import com.example.storage_app.model.FileRecord;
import com.example.storage_app.repository.FileRecordRepository;
import com.example.storage_app.util.FileMapper;
import com.example.storage_app.util.FileMetadataBuilder;
import com.example.storage_app.util.FileStorageResult;
import com.example.storage_app.util.GridFsHelper;
import com.mongodb.MongoWriteException;
import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileServiceImpl implements FileService {
  private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

  private final GridFsTemplate gridFsTemplate;
  private final FileMetadataBuilder fileMetadataBuilder;
  private final FileMapper fileMapper;
  private final GridFsHelper gridFsHelper;
  private final FileRecordRepository fileRecordRepository;

  public FileServiceImpl(
      GridFsTemplate gridFsTemplate,
      FileMetadataBuilder fileMetadataBuilder,
      FileMapper fileMapper,
      GridFsHelper gridFsHelper,
      FileRecordRepository fileRecordRepository) {
    this.gridFsTemplate = gridFsTemplate;
    this.fileMetadataBuilder = fileMetadataBuilder;
    this.fileMapper = fileMapper;
    this.gridFsHelper = gridFsHelper;
    this.fileRecordRepository = fileRecordRepository;
  }

  @Override
  @Transactional
  public FileResponse uploadFile(String userId, MultipartFile file, FileUploadRequest request)
      throws NoSuchAlgorithmException, IOException {
    log.info("--- FileServiceImpl.uploadFile START ---");
    log.info(
        "Input userId: {}, originalFilename from MultipartFile: {}, filename from Request DTO: {}",
        userId,
        file.getOriginalFilename(),
        request.filename());

    if (file.isEmpty()) {
      throw new InvalidRequestArgumentException("File is empty");
    }

    FileRecord record = fileMetadataBuilder.build(request, userId, file);
    log.info(
        "Built FileRecord (initial): id={}, ownerId={}, originalFilename={}, sha256(initial)={}, token={}",
        record.getId(),
        record.getOwnerId(),
        record.getOriginalFilename(),
        record.getSha256(),
        record.getToken());

    log.info("Proceeding to storeAndHash for originalFilename: {}", record.getOriginalFilename());

    FileStorageResult storageResult;
    try {
      storageResult = gridFsHelper.storeAndHash(file, record);
    } catch (MongoWriteException e) {
      log.error(
          "MongoWriteException from gridFsHelper.storeAndHash for originalFilename: {}. Code: {}, Message: {}",
          record.getOriginalFilename(),
          e.getCode(),
          e.getMessage(),
          e);
      if (e.getCode() == 11000) {
        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (errorMessage.contains("owner_filename_idx")
            || errorMessage.contains("metadata.originalfilename")) {
          throw new FileAlreadyExistsException(
              "Filename '" + record.getOriginalFilename() + "' already exists for this user.", e);
        } else if (errorMessage.contains("owner_sha256_idx")
            || errorMessage.contains("metadata.sha256")) {
          String hashInvolved =
              record.getSha256() != null && !record.getSha256().isBlank()
                  ? record.getSha256()
                  : "with provided content";
          throw new FileAlreadyExistsException(
              "Content with hash '" + hashInvolved + "' already exists for this user.", e);
        } else {
          throw new FileAlreadyExistsException(
              "A file with this name or content already exists for the user (DB conflict).", e);
        }
      }
      throw new StorageException("MongoDB write error during file storage.", e);
    } catch (IOException | NoSuchAlgorithmException e) {
      log.error("Error during file hashing or GridFS storage: {}", e.getMessage(), e);
      if (e instanceof IOException) throw (IOException) e;
      if (e instanceof NoSuchAlgorithmException) throw (NoSuchAlgorithmException) e;
      throw new StorageException("Error processing file for storage.", e);
    }

    log.info(
        "storeAndHash result: gridFSFileId={}, storedSha256={}, storedContentType={}, storedSize={}",
        storageResult.id,
        storageResult.sha256,
        storageResult.contentType,
        storageResult.size);
    log.info(
        "FileRecord object state after storeAndHash: id (systemUUID)={}, record.sha256 (pre-calc)={}, originalFilename={}",
        record.getId(),
        record.getSha256(),
        record.getOriginalFilename());

    record.setContentType(storageResult.contentType);
    record.setSize(storageResult.size);
    record.setSha256(storageResult.sha256);

    log.info(
        "FileRecord state before mapping to response: id={}, ownerId={}, originalFilename={}, sha256={}, contentType={}, size={}, token={}",
        record.getId(),
        record.getOwnerId(),
        record.getOriginalFilename(),
        record.getSha256(),
        record.getContentType(),
        record.getSize(),
        record.getToken());

    FileResponse response = fileMapper.fromEntity(record);
    return response;
  }

  private String mapSortField(String apiSortField) {
    if (apiSortField == null) {
      return "uploadDate";
    }
    return switch (apiSortField.toLowerCase()) {
      case "filename" -> "originalFilename";
      case "uploaddate" -> "uploadDate";
      case "contenttype" -> "contentType";
      case "size" -> "size";
      case "tag", "tags" -> "tags";
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

    Page<FileRecord> pageOfRecords;
    if (userId != null) {
      if (filterTag != null && !filterTag.isBlank()) {
        pageOfRecords =
            fileRecordRepository.findByOwnerIdAndTagsContaining(
                userId, filterTag.toLowerCase(), pageable);
      } else {
        pageOfRecords = fileRecordRepository.findByOwnerId(userId, pageable);
      }
    } else {
      if (filterTag != null && !filterTag.isBlank()) {
        pageOfRecords =
            fileRecordRepository.findByVisibilityAndTagsContaining(
                "PUBLIC", filterTag.toLowerCase(), pageable);
      } else {
        pageOfRecords = fileRecordRepository.findByVisibility("PUBLIC", pageable);
      }
    }
    return pageOfRecords.map(fileMapper::fromEntity);
  }

  @Override
  public ResponseEntity<GridFsResource> downloadFile(String token) throws IOException {
    FileRecord record =
        fileRecordRepository
            .findByToken(token)
            .orElseThrow(() -> new ResourceNotFoundException("File not found for token: " + token));

    Query query = Query.query(Criteria.where("filename").is(record.getFilename()));
    GridFSFile gridFSFile = gridFsTemplate.findOne(query);

    if (gridFSFile == null) {
      throw new ResourceNotFoundException(
          "File content not found in GridFS for system file: " + record.getFilename());
    }

    GridFsResource resource = gridFsTemplate.getResource(gridFSFile);
    if (resource == null || !resource.exists() || !resource.isReadable()) {
      throw new StorageException(
          "Failed to retrieve file content for: "
              + record.getOriginalFilename()
              + " or file is not readable.");
    }

    HttpHeaders headers = new HttpHeaders();
    headers.add(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"" + record.getOriginalFilename() + "\"");
    try {
      headers.setContentType(MediaType.parseMediaType(record.getContentType()));
    } catch (InvalidMediaTypeException e) {
      headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    }

    return new ResponseEntity<>(resource, headers, HttpStatus.OK);
  }

  @Override
  @Transactional
  public FileResponse updateFileDetails(String userId, String fileId, FileUpdateRequest request) {
    log.info("Attempting to find file by system UUID (filename): {}", fileId);
    FileRecord record =
        fileRecordRepository
            .findByFilename(fileId) // Use findByFilename (system UUID)
            .orElseThrow(() -> new ResourceNotFoundException("File not found with id: " + fileId));

    if (!userId.equals(record.getOwnerId())) {
      throw new UnauthorizedOperationException(
          "User '" + userId + "' not authorized to update fileId: " + fileId);
    }

    String newOriginalFilename = request.newFilename();
    if (newOriginalFilename == null
        || newOriginalFilename.isBlank()
        || newOriginalFilename.equals(record.getOriginalFilename())) {
      return fileMapper.fromEntity(record); // No change needed or invalid new name
    }

    log.info(
        "Updating originalFilename for fileId {} from '{}' to '{}'",
        fileId,
        record.getOriginalFilename(),
        newOriginalFilename);
    record.setOriginalFilename(newOriginalFilename);

    try {
      FileRecord updatedRecord = fileRecordRepository.save(record);
      log.info(
          "FileRecord updated and saved. New originalFilename: {}",
          updatedRecord.getOriginalFilename());
      return fileMapper.fromEntity(updatedRecord);
    } catch (
        DuplicateKeyException e) { // Catch DKE from repository.save if unique index is violated
      log.warn(
          "DuplicateKeyException on updating filename for fileId {}: {}", fileId, e.getMessage());
      throw new FileAlreadyExistsException(
          "Filename '"
              + newOriginalFilename
              + "' already exists for this user (filename conflict during update).",
          e);
    } catch (DataAccessException e) {
      log.error(
          "DataAccessException on updating filename for fileId {}: {}", fileId, e.getMessage(), e);
      throw new StorageException("Failed to update file metadata: " + e.getMessage(), e);
    }
  }

  @Override
  @Transactional
  public void deleteFile(String userId, String fileId) {
    log.info("Attempting to delete file with system UUID (filename): {}", fileId);
    FileRecord record =
        fileRecordRepository
            .findByFilename(fileId) // Use findByFilename (system UUID)
            .orElseThrow(() -> new ResourceNotFoundException("File not found with id: " + fileId));

    if (!userId.equals(record.getOwnerId())) {
      log.warn(
          "Unauthorized delete attempt: User '{}' on fileId '{}' owned by '{}'",
          userId,
          fileId,
          record.getOwnerId());
      throw new UnauthorizedOperationException(
          "User '" + userId + "' not authorized to delete fileId: " + fileId);
    }

    // IMPORTANT: We must delete by _id (not filename) to ensure all file chunks are removed from
    // fs.chunks.
    // Deleting by filename alone may leave orphaned chunks, causing storage leaks and data
    // inconsistency.
    // See: https://www.mongodb.com/docs/manual/core/gridfs/#deleting-files-from-gridfs
    Query gridFsQuery = Query.query(Criteria.where("filename").is(record.getFilename()));
    GridFSFile gridFSFile = gridFsTemplate.findOne(gridFsQuery);

    if (gridFSFile != null) {
      log.info(
          "Found GridFSFile with ObjectId: {} and filename (systemUUID): {}. Deleting from GridFS.",
          gridFSFile.getObjectId(),
          gridFSFile.getFilename());
      gridFsTemplate.delete(Query.query(Criteria.where("_id").is(gridFSFile.getObjectId())));
    } else {
      log.warn(
          "GridFSFile not found for filename (systemUUID): {} during delete operation. FileRecord might be orphaned or GridFS file already deleted.",
          record.getFilename());
    }

    // Delete the FileRecord itself from the repository.
    // This should remove the document from fs.files based on its primary key (which is string UUID
    // if mapping works as expected).
    log.info("Deleting FileRecord with id (systemUUID): {}", record.getId());
    fileRecordRepository.delete(record); // Use delete(entity) for safety if ID is complex.
    log.info("Successfully deleted file with system UUID: {}", fileId);
  }
}
