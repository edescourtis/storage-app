package com.example.storage_app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.exception.*;
import com.example.storage_app.model.FileRecord;
import com.example.storage_app.model.Visibility;
import com.example.storage_app.repository.FileRecordRepository;
import com.example.storage_app.util.FileMapper;
import com.example.storage_app.util.FileMetadataBuilder;
import com.example.storage_app.util.GridFsHelper;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteError;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.result.UpdateResult;
import java.io.IOException;
import java.util.Date;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class FileServiceImpl3Test {

  @Mock private GridFsTemplate gridFsTemplate;

  @Mock
  private FileMetadataBuilder
      fileMetadataBuilder; // Though not directly used in download, it's a dependency

  @Mock private FileMapper fileMapper; // Though not directly used in download, it's a dependency

  @Mock
  private GridFsHelper gridFsHelper; // Though not directly used in download, it's a dependency

  @Mock private FileRecordRepository fileRecordRepository;

  @Mock private MongoTemplate mongoTemplate;

  @InjectMocks private FileServiceImpl fileService;

  private FileRecord mockFileRecord;
  private GridFSFile mockGridFSFile;
  @Mock private GridFsResource mockGridFsResource;

  // Additional Mocks for updateFileDetails
  @Mock private FileUploadRequest mockFileUploadRequest; // For creating initial record if needed
  @Mock private MultipartFile mockMultipartFile; // For creating initial record if needed

  @BeforeEach
  void setUp() {
    mockFileRecord = new FileRecord();
    mockFileRecord.setFilename("system-uuid-filename");
    mockFileRecord.setOriginalFilename("user_friendly_name.txt");
    mockFileRecord.setContentType("text/plain");
    mockFileRecord.setToken("test-token");

    mockGridFSFile =
        new GridFSFile(
            new BsonObjectId(new ObjectId()),
            "system-uuid-filename",
            0L,
            0,
            new Date(),
            new Document());

    // mockGridFsResource is already a mock

    // Leniently mock mongoTemplate.updateFirst to return a successful update by default
    lenient()
        .when(
            mongoTemplate.updateFirst(
                any(Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                eq(FileRecord.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    lenient()
        .when(
            mongoTemplate.updateFirst(
                any(Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                eq("fs.files")))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
  }

  @Test
  void testDownloadFile_success() throws IOException {
    when(fileRecordRepository.findByToken("test-token")).thenReturn(Optional.of(mockFileRecord));
    when(gridFsTemplate.findOne(any(Query.class))).thenReturn(mockGridFSFile);
    when(gridFsTemplate.getResource((com.mongodb.client.gridfs.model.GridFSFile) mockGridFSFile))
        .thenReturn(mockGridFsResource);
    when(mockGridFsResource.exists()).thenReturn(true);
    when(mockGridFsResource.isReadable()).thenReturn(true);

    ResponseEntity<GridFsResource> response = fileService.downloadFile("test-token");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(mockGridFsResource, response.getBody());
    assertTrue(response.getHeaders().containsKey(HttpHeaders.CONTENT_DISPOSITION));
    assertEquals(
        "attachment; filename=\"user_friendly_name.txt\"",
        response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());

    verify(fileRecordRepository).findByToken("test-token");
    verify(gridFsTemplate).findOne(any(Query.class));
    verify(gridFsTemplate).getResource((com.mongodb.client.gridfs.model.GridFSFile) mockGridFSFile);
  }

  @Test
  void testDownloadFile_whenTokenNotFound_throwsResourceNotFoundException() {
    when(fileRecordRepository.findByToken("non-existent-token")).thenReturn(Optional.empty());

    Exception exception =
        assertThrows(
            ResourceNotFoundException.class,
            () -> {
              fileService.downloadFile("non-existent-token");
            });

    assertEquals("File not found for token: non-existent-token", exception.getMessage());
    verify(fileRecordRepository).findByToken("non-existent-token");
    verifyNoInteractions(gridFsTemplate);
  }

  @Test
  void testDownloadFile_whenGridFsResourceIsNull_throwsStorageException() {
    when(fileRecordRepository.findByToken("test-token")).thenReturn(Optional.of(mockFileRecord));
    when(gridFsTemplate.findOne(any(Query.class))).thenReturn(mockGridFSFile);
    when(gridFsTemplate.getResource(mockGridFSFile)).thenReturn(null);

    Exception exception =
        assertThrows(
            StorageException.class,
            () -> {
              fileService.downloadFile("test-token");
            });

    assertTrue(
        exception
            .getMessage()
            .contains("Failed to retrieve file content for: user_friendly_name.txt"));
  }

  @Test
  void testDownloadFile_whenGridFsResourceNotExists_throwsStorageException() {
    when(fileRecordRepository.findByToken("test-token")).thenReturn(Optional.of(mockFileRecord));
    when(gridFsTemplate.findOne(any(Query.class))).thenReturn(mockGridFSFile);
    when(gridFsTemplate.getResource(mockGridFSFile)).thenReturn(mockGridFsResource);
    when(mockGridFsResource.exists()).thenReturn(false);

    Exception exception =
        assertThrows(
            StorageException.class,
            () -> {
              fileService.downloadFile("test-token");
            });
    assertTrue(
        exception
            .getMessage()
            .contains("Failed to retrieve file content for: user_friendly_name.txt"));
  }

  @Test
  void testDownloadFile_whenGridFsResourceNotReadable_throwsStorageException() {
    when(fileRecordRepository.findByToken("test-token")).thenReturn(Optional.of(mockFileRecord));
    when(gridFsTemplate.findOne(any(Query.class))).thenReturn(mockGridFSFile);
    when(gridFsTemplate.getResource(mockGridFSFile)).thenReturn(mockGridFsResource);
    when(mockGridFsResource.exists()).thenReturn(true);
    when(mockGridFsResource.isReadable()).thenReturn(false);

    Exception exception =
        assertThrows(
            StorageException.class,
            () -> {
              fileService.downloadFile("test-token");
            });
    assertTrue(
        exception
            .getMessage()
            .contains("Failed to retrieve file content for: user_friendly_name.txt"));
  }

  @Test
  void testDownloadFile_whenInvalidContentType_usesOctetStream() throws IOException {
    String invalidContentTypeString = "utterly/invalidMediaTypeStringWhichShouldCauseException";
    mockFileRecord.setContentType(invalidContentTypeString);
    when(fileRecordRepository.findByToken("test-token")).thenReturn(Optional.of(mockFileRecord));
    when(gridFsTemplate.findOne(any(Query.class))).thenReturn(mockGridFSFile);
    when(gridFsTemplate.getResource((com.mongodb.client.gridfs.model.GridFSFile) mockGridFSFile))
        .thenReturn(mockGridFsResource);
    when(mockGridFsResource.exists()).thenReturn(true);
    when(mockGridFsResource.isReadable()).thenReturn(true);

    ResponseEntity<GridFsResource> response = fileService.downloadFile("test-token");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(
        MediaType.parseMediaType(invalidContentTypeString), response.getHeaders().getContentType());
  }

  // Tests for updateFileDetails
  @Test
  void testUpdateFileDetails_success() {
    String userId = "user-123";
    String fileId = "system-uuid-for-update";
    FileUpdateRequest updateRequest = new FileUpdateRequest("new_updated_filename.txt");

    FileRecord existingRecord = new FileRecord();
    existingRecord.setId(fileId);
    existingRecord.setFilename(fileId); // system UUID is stored as filename in FileRecord
    existingRecord.setOwnerId(userId);
    existingRecord.setOriginalFilename("old_filename.txt");

    FileRecord savedRecord = new FileRecord(); // Simulates the record after saving
    savedRecord.setId(fileId);
    savedRecord.setFilename(fileId);
    savedRecord.setOwnerId(userId);
    savedRecord.setOriginalFilename("new_updated_filename.txt"); // updated name

    FileResponse expectedResponse =
        new FileResponse(
            fileId, "new_updated_filename.txt", Visibility.PUBLIC, null, null, null, 0L, null);

    when(fileRecordRepository.findByFilename(fileId)).thenReturn(Optional.of(existingRecord));
    // Mock mongoTemplate.updateFirst for success
    when(mongoTemplate.updateFirst(
            any(Query.class),
            argThat(
                update ->
                    update
                        .getUpdateObject()
                        .get("$set", Document.class)
                        .getString("metadata.originalFilename")
                        .equals("new_updated_filename.txt")),
            eq("fs.files")))
        .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
    when(fileMapper.fromEntity(
            argThat(record -> record.getOriginalFilename().equals("new_updated_filename.txt"))))
        .thenReturn(expectedResponse);

    FileResponse actualResponse = fileService.updateFileDetails(userId, fileId, updateRequest);

    assertEquals(expectedResponse, actualResponse);
    verify(fileRecordRepository).findByFilename(fileId);
    // Verify mongoTemplate.updateFirst was called
    verify(mongoTemplate)
        .updateFirst(
            any(Query.class),
            argThat(
                update ->
                    update
                        .getUpdateObject()
                        .get("$set", Document.class)
                        .getString("metadata.originalFilename")
                        .equals("new_updated_filename.txt")),
            eq("fs.files"));
    verify(fileMapper)
        .fromEntity(
            argThat(record -> record.getOriginalFilename().equals("new_updated_filename.txt")));
  }

  @Test
  void testUpdateFileDetails_whenFileIdNotFound_throwsResourceNotFoundException() {
    String userId = "user-123";
    String fileId = "non-existent-file-id";
    FileUpdateRequest updateRequest = new FileUpdateRequest("new_name.txt");

    when(fileRecordRepository.findByFilename(fileId)).thenReturn(Optional.empty());

    Exception exception =
        assertThrows(
            ResourceNotFoundException.class,
            () -> {
              fileService.updateFileDetails(userId, fileId, updateRequest);
            });

    assertEquals("File not found with id: " + fileId, exception.getMessage());
    verify(fileRecordRepository).findByFilename(fileId);
    verify(fileRecordRepository, never()).save(any());
  }

  @Test
  void testUpdateFileDetails_whenUserNotOwner_throwsUnauthorizedOperationException() {
    String userId = "attacker-user";
    String fileId = "owned-by-another";
    FileUpdateRequest updateRequest = new FileUpdateRequest("new_name.txt");

    FileRecord existingRecord = new FileRecord();
    existingRecord.setId(fileId);
    existingRecord.setFilename(fileId);
    existingRecord.setOwnerId("actual-owner-user");
    existingRecord.setOriginalFilename("original_name.txt");

    when(fileRecordRepository.findByFilename(fileId)).thenReturn(Optional.of(existingRecord));

    Exception exception =
        assertThrows(
            UnauthorizedOperationException.class,
            () -> {
              fileService.updateFileDetails(userId, fileId, updateRequest);
            });

    assertEquals(
        "User '" + userId + "' not authorized to update fileId: " + fileId, exception.getMessage());
    verify(fileRecordRepository).findByFilename(fileId);
    verify(fileRecordRepository, never()).save(any());
  }

  @Test
  void testUpdateFileDetails_whenNewFilenameIsNull_returnsUnchangedRecord() {
    String userId = "user-123";
    String fileId = "file-id-name-null";
    FileUpdateRequest updateRequest = new FileUpdateRequest(null);

    FileRecord existingRecord = new FileRecord();
    existingRecord.setId(fileId);
    existingRecord.setFilename(fileId);
    existingRecord.setOwnerId(userId);
    existingRecord.setOriginalFilename("original_name.txt");

    FileResponse mappedResponse =
        new FileResponse(
            fileId, "original_name.txt", Visibility.PRIVATE, null, null, null, 0L, null);
    when(fileMapper.fromEntity(existingRecord)).thenReturn(mappedResponse);

    when(fileRecordRepository.findByFilename(fileId)).thenReturn(Optional.of(existingRecord));

    FileResponse actualResponse = fileService.updateFileDetails(userId, fileId, updateRequest);

    assertEquals(existingRecord.getOriginalFilename(), actualResponse.filename());
    verify(fileRecordRepository).findByFilename(fileId);
    verify(fileRecordRepository, never()).save(any());
    verify(fileMapper).fromEntity(existingRecord);
  }

  @Test
  void testUpdateFileDetails_whenNewFilenameIsBlank_returnsUnchangedRecord() {
    String userId = "user-123";
    String fileId = "file-id-name-blank";
    FileUpdateRequest updateRequest = new FileUpdateRequest("  ");

    FileRecord existingRecord = new FileRecord();
    existingRecord.setId(fileId);
    existingRecord.setFilename(fileId);
    existingRecord.setOwnerId(userId);
    existingRecord.setOriginalFilename("original_name.txt");

    FileResponse mappedResponse =
        new FileResponse(
            fileId, "original_name.txt", Visibility.PRIVATE, null, null, null, 0L, null);
    when(fileMapper.fromEntity(existingRecord)).thenReturn(mappedResponse);
    when(fileRecordRepository.findByFilename(fileId)).thenReturn(Optional.of(existingRecord));

    FileResponse actualResponse = fileService.updateFileDetails(userId, fileId, updateRequest);

    assertEquals(existingRecord.getOriginalFilename(), actualResponse.filename());
    verify(fileRecordRepository).findByFilename(fileId);
    verify(fileRecordRepository, never()).save(any());
    verify(fileMapper).fromEntity(existingRecord);
  }

  @Test
  void testUpdateFileDetails_whenNewFilenameIsSameAsOld_returnsUnchangedRecord() {
    String userId = "user-123";
    String fileId = "file-id-name-same";
    String originalName = "original_name.txt";
    FileUpdateRequest updateRequest = new FileUpdateRequest(originalName);

    FileRecord existingRecord = new FileRecord();
    existingRecord.setId(fileId);
    existingRecord.setFilename(fileId);
    existingRecord.setOwnerId(userId);
    existingRecord.setOriginalFilename(originalName);

    FileResponse mappedResponse =
        new FileResponse(fileId, originalName, Visibility.PRIVATE, null, null, null, 0L, null);
    when(fileMapper.fromEntity(existingRecord)).thenReturn(mappedResponse);
    when(fileRecordRepository.findByFilename(fileId)).thenReturn(Optional.of(existingRecord));

    FileResponse actualResponse = fileService.updateFileDetails(userId, fileId, updateRequest);

    assertEquals(originalName, actualResponse.filename());
    verify(fileRecordRepository).findByFilename(fileId);
    verify(fileRecordRepository, never()).save(any());
    verify(fileMapper).fromEntity(existingRecord);
  }

  @Test
  void testUpdateFileDetails_whenNewFilenameConflicts_throwsFileAlreadyExistsException() {
    String userId = "user-123";
    String fileId = "file-id-for-conflict";
    String newConflictingName = "conflicting_name.txt";
    FileUpdateRequest updateRequest = new FileUpdateRequest(newConflictingName);

    FileRecord existingRecord = new FileRecord();
    existingRecord.setId(fileId);
    existingRecord.setFilename(fileId);
    existingRecord.setOwnerId(userId);
    existingRecord.setOriginalFilename("old_name.txt");

    when(fileRecordRepository.findByFilename(fileId)).thenReturn(Optional.of(existingRecord));
    // Simulate DuplicateKeyException when mongoTemplate.updateFirst is called
    when(mongoTemplate.updateFirst(
            any(Query.class),
            argThat(
                update ->
                    update
                        .getUpdateObject()
                        .get("$set", Document.class)
                        .getString("metadata.originalFilename")
                        .equals(newConflictingName)),
            eq("fs.files")))
        .thenThrow(new DuplicateKeyException("Simulated DKE for owner_filename_idx"));

    Exception exception =
        assertThrows(
            FileAlreadyExistsException.class,
            () -> {
              fileService.updateFileDetails(userId, fileId, updateRequest);
            });

    assertTrue(
        exception
            .getMessage()
            .contains("Filename '" + newConflictingName + "' already exists for this user"));
    verify(fileRecordRepository).findByFilename(fileId);
    // Verify mongoTemplate.updateFirst was called
    verify(mongoTemplate)
        .updateFirst(
            any(Query.class),
            argThat(
                update ->
                    update
                        .getUpdateObject()
                        .get("$set", Document.class)
                        .getString("metadata.originalFilename")
                        .equals(newConflictingName)),
            eq("fs.files"));
  }

  @Test
  void
      testUpdateFileDetails_whenRepositorySaveFailsWithDataAccessException_throwsStorageException() {
    String userId = "user-123";
    String fileId = "file-id-data-access-ex";
    String newName = "new_name_for_data_access_ex.txt";
    FileUpdateRequest updateRequest = new FileUpdateRequest(newName);

    FileRecord existingRecord = new FileRecord();
    existingRecord.setId(fileId);
    existingRecord.setFilename(fileId);
    existingRecord.setOwnerId(userId);
    existingRecord.setOriginalFilename("old_name.txt");

    when(fileRecordRepository.findByFilename(fileId)).thenReturn(Optional.of(existingRecord));
    // Simulate DataAccessException from mongoTemplate.updateFirst
    when(mongoTemplate.updateFirst(
            any(Query.class),
            argThat(
                update ->
                    update
                        .getUpdateObject()
                        .get("$set", Document.class)
                        .getString("metadata.originalFilename")
                        .equals(newName)),
            eq("fs.files")))
        .thenThrow(
            new org.springframework.dao.DataAccessException("Simulated DataAccessException") {});

    Exception exception =
        assertThrows(
            StorageException.class,
            () -> {
              fileService.updateFileDetails(userId, fileId, updateRequest);
            });

    assertTrue(exception.getMessage().contains("Failed to update file metadata"));
    verify(fileRecordRepository).findByFilename(fileId);
    // Verify mongoTemplate.updateFirst was called
    verify(mongoTemplate)
        .updateFirst(
            any(Query.class),
            argThat(
                update ->
                    update
                        .getUpdateObject()
                        .get("$set", Document.class)
                        .getString("metadata.originalFilename")
                        .equals(newName)),
            eq("fs.files"));
  }

  // Tests for uploadFile method

  @Test
  void testUploadFile_whenFileIsEmpty_throwsInvalidRequestArgumentException() {
    String userId = "user-empty-file";
    FileUploadRequest uploadRequest = new FileUploadRequest("empty.txt", Visibility.PUBLIC, null);
    when(mockMultipartFile.isEmpty()).thenReturn(true);

    Exception exception =
        assertThrows(
            InvalidRequestArgumentException.class,
            () -> {
              fileService.uploadFile(userId, mockMultipartFile, uploadRequest);
            });

    assertEquals("File is empty", exception.getMessage());
    verifyNoInteractions(fileMetadataBuilder, gridFsHelper, fileRecordRepository, fileMapper);
  }

  @Test
  void testUploadFile_whenMongoWriteExceptionNotDuplicateKey_throwsStorageException()
      throws Exception {
    String userId = "user-mongo-generic-ex";
    String filename = "mongo_generic_ex.txt";
    FileUploadRequest uploadRequest = new FileUploadRequest(filename, Visibility.PUBLIC, null);
    FileRecord record = new FileRecord();
    record.setOriginalFilename(filename);

    when(mockMultipartFile.isEmpty()).thenReturn(false);
    when(fileMetadataBuilder.build(uploadRequest, userId, mockMultipartFile)).thenReturn(record);

    MongoWriteException mockMongoWriteException = mock(MongoWriteException.class);
    WriteError mockWriteError = new WriteError(123, "Some other mongo error", new BsonDocument());
    lenient().when(mockMongoWriteException.getError()).thenReturn(mockWriteError);
    lenient().when(mockMongoWriteException.getCode()).thenReturn(123);

    when(gridFsHelper.storeAndHash(mockMultipartFile, record)).thenThrow(mockMongoWriteException);

    Exception exception =
        assertThrows(
            StorageException.class,
            () -> {
              fileService.uploadFile(userId, mockMultipartFile, uploadRequest);
            });

    assertEquals("MongoDB write error during file storage.", exception.getMessage());
    assertInstanceOf(MongoWriteException.class, exception.getCause());
  }

  @Test
  void
      testUploadFile_whenMongoWriteExceptionIsDuplicateKeyButUnknownIndex_throwsGenericFileAlreadyExistsException()
          throws Exception {
    String userId = "user-mongo-unknown-dup-idx";
    String filename = "mongo_unknown_dup_idx.txt";
    FileUploadRequest uploadRequest = new FileUploadRequest(filename, Visibility.PUBLIC, null);
    FileRecord record = new FileRecord();
    record.setOriginalFilename(filename);

    when(mockMultipartFile.isEmpty()).thenReturn(false);
    when(fileMetadataBuilder.build(uploadRequest, userId, mockMultipartFile)).thenReturn(record);

    MongoWriteException mockMongoWriteException = mock(MongoWriteException.class);
    WriteError mockWriteError =
        new WriteError(
            11000,
            "E11000 duplicate key error collection: test.fs.files index: some_other_unknown_idx dup key: { : \"value\" }",
            new BsonDocument());
    lenient().when(mockMongoWriteException.getError()).thenReturn(mockWriteError);
    lenient().when(mockMongoWriteException.getCode()).thenReturn(11000);

    when(gridFsHelper.storeAndHash(mockMultipartFile, record)).thenThrow(mockMongoWriteException);

    Exception exception =
        assertThrows(
            FileAlreadyExistsException.class,
            () -> {
              fileService.uploadFile(userId, mockMultipartFile, uploadRequest);
            });

    assertEquals(
        "A file with this name or content already exists for the user (DB conflict).",
        exception.getMessage());
  }

  @Test
  void testUploadFile_whenStoreAndHashThrowsIOException_rethrowsIOException() throws Exception {
    String userId = "user-io-ex";
    String filename = "io_ex.txt";
    FileUploadRequest uploadRequest = new FileUploadRequest(filename, Visibility.PUBLIC, null);
    FileRecord record = new FileRecord();

    when(mockMultipartFile.isEmpty()).thenReturn(false);
    when(fileMetadataBuilder.build(uploadRequest, userId, mockMultipartFile)).thenReturn(record);
    IOException ioException = new IOException("Simulated IO error during store/hash");
    when(gridFsHelper.storeAndHash(mockMultipartFile, record)).thenThrow(ioException);

    Exception exception =
        assertThrows(
            IOException.class,
            () -> {
              fileService.uploadFile(userId, mockMultipartFile, uploadRequest);
            });

    assertEquals("Simulated IO error during store/hash", exception.getMessage());
  }

  @Test
  void
      testUploadFile_whenStoreAndHashThrowsNoSuchAlgorithmException_rethrowsNoSuchAlgorithmException()
          throws Exception {
    String userId = "user-no-such-algo-ex";
    String filename = "no_such_algo_ex.txt";
    FileUploadRequest uploadRequest = new FileUploadRequest(filename, Visibility.PUBLIC, null);
    FileRecord record = new FileRecord();

    when(mockMultipartFile.isEmpty()).thenReturn(false);
    when(fileMetadataBuilder.build(uploadRequest, userId, mockMultipartFile)).thenReturn(record);
    java.security.NoSuchAlgorithmException noSuchAlgorithmException =
        new java.security.NoSuchAlgorithmException("Simulated NoSuchAlgorithmException");
    when(gridFsHelper.storeAndHash(mockMultipartFile, record)).thenThrow(noSuchAlgorithmException);

    Exception exception =
        assertThrows(
            java.security.NoSuchAlgorithmException.class,
            () -> {
              fileService.uploadFile(userId, mockMultipartFile, uploadRequest);
            });

    assertEquals("Simulated NoSuchAlgorithmException", exception.getMessage());
  }
}
