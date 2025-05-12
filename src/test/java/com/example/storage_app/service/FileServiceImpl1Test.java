package com.example.storage_app.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.exception.FileAlreadyExistsException;
import com.example.storage_app.exception.InvalidRequestArgumentException;
import com.example.storage_app.exception.ResourceNotFoundException;
import com.example.storage_app.exception.StorageException;
import com.example.storage_app.exception.UnauthorizedOperationException;
import com.example.storage_app.model.FileRecord;
import com.example.storage_app.model.Visibility;
import com.example.storage_app.repository.FileRecordRepository;
import com.example.storage_app.util.FileMapper;
import com.example.storage_app.util.FileMetadataBuilder;
import com.example.storage_app.util.FileStorageResult;
import com.example.storage_app.util.GridFsHelper;
import com.example.storage_app.util.MimeUtil;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.tika.io.LookaheadInputStream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class FileServiceImpl1Test {
  @Mock private GridFsTemplate gridFsTemplate;
  @Mock private FileRecordRepository fileRecordRepository;
  @Mock private FileMetadataBuilder fileMetadataBuilder;
  @Mock private FileMapper fileMapper;
  @Mock private GridFsHelper gridFsHelper;

  @InjectMocks private FileServiceImpl fileService;

  @Mock private MultipartFile mockMultipartFile;

  private FileUploadRequest defaultUploadRequest;
  private String testUserId = "user123";
  private String testContent = "This is test content.";
  private String testToken = "test-token-123";
  private String testFileId;
  private String newFilename = "new_document.txt";

  private static final Pattern UUID_REGEX =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private boolean isValidUUID(String s) {
    if (s == null) return false;
    return UUID_REGEX.matcher(s).matches();
  }

  @BeforeEach
  void setUp() throws Exception {
    testUserId = "user123";
    newFilename = "new_document.txt";
    testContent = "This is test content.";
    testToken = "test-token-123";
    defaultUploadRequest =
        new FileUploadRequest("user_requested_file.txt", Visibility.PRIVATE, List.of("tag1"));
    testFileId = UUID.randomUUID().toString();

    lenient()
        .when(mockMultipartFile.getOriginalFilename())
        .thenReturn("original_multipart_filename.txt");
    lenient().when(mockMultipartFile.isEmpty()).thenReturn(false);
    lenient().when(mockMultipartFile.getSize()).thenReturn((long) testContent.getBytes().length);
    lenient().when(mockMultipartFile.getContentType()).thenReturn("text/plain");
    lenient()
        .when(mockMultipartFile.getInputStream())
        .thenReturn(new ByteArrayInputStream(testContent.getBytes()));

    lenient()
        .when(
            fileMetadataBuilder.build(
                any(FileUploadRequest.class), anyString(), any(MultipartFile.class)))
        .thenAnswer(
            invocation -> {
              FileUploadRequest fur = invocation.getArgument(0);
              String uid = invocation.getArgument(1);
              MultipartFile mf = invocation.getArgument(2);

              String determinedOriginalFilename;
              if (fur.filename() != null) { // Filename provided in request
                determinedOriginalFilename =
                    fur.filename(); // Use it as is, even if blank like "   "
              } else { // Filename not in request, use multipart's
                determinedOriginalFilename = mf.getOriginalFilename();
                // If multipart's filename is also null or truly blank (empty or only whitespace),
                // then default
                if (determinedOriginalFilename == null || determinedOriginalFilename.isBlank()) {
                  determinedOriginalFilename = "default_from_builder.txt";
                }
              }

              List<String> tags = fur.tags();
              if (tags != null && tags.size() > 5) {
                throw new InvalidRequestArgumentException("A maximum of 5 tags are allowed.");
              }
              String systemUUID = UUID.randomUUID().toString();
              return FileRecord.builder()
                  .id(systemUUID)
                  .filename(systemUUID)
                  .originalFilename(determinedOriginalFilename)
                  .ownerId(uid)
                  .visibility(fur.visibility())
                  .tags(
                      tags != null
                          ? tags.stream()
                              .filter(Objects::nonNull)
                              .map(String::toLowerCase)
                              .collect(Collectors.toList())
                          : Collections.emptyList())
                  .contentType(
                      mf.getContentType() != null
                          ? mf.getContentType()
                          : "application/octet-stream")
                  .size(mf.getSize())
                  .uploadDate(new Date())
                  .token(UUID.randomUUID().toString())
                  .sha256("mocksha256-for-" + determinedOriginalFilename)
                  .build();
            });

    lenient()
        .when(gridFsHelper.storeAndHash(any(MultipartFile.class), any(FileRecord.class)))
        .thenAnswer(
            (Answer<FileStorageResult>)
                invocation -> {
                  FileRecord record = invocation.getArgument(1);
                  MultipartFile passedFile = invocation.getArgument(0);
                  String oFilename =
                      record != null ? record.getOriginalFilename() : "default_from_helper.txt";
                  String cType =
                      passedFile.getContentType() != null
                          ? passedFile.getContentType()
                          : "text/plain";
                  long size = passedFile.getSize();
                  String actualSha256 =
                      record.getSha256() != null
                          ? record.getSha256()
                          : "dummy-helper-sha-" + UUID.randomUUID();
                  return new FileStorageResult(
                      new ObjectId(),
                      actualSha256,
                      cType,
                      size,
                      new Document("originalFilename", oFilename).append("sha256", actualSha256));
                });

    lenient()
        .when(fileRecordRepository.existsByOwnerIdAndOriginalFilename(anyString(), anyString()))
        .thenReturn(false);
    lenient()
        .when(fileRecordRepository.existsByOwnerIdAndSha256(anyString(), anyString()))
        .thenReturn(false);
    lenient()
        .when(fileRecordRepository.save(any(FileRecord.class)))
        .thenAnswer(
            invocation -> {
              FileRecord recordToSave = invocation.getArgument(0);
              if (recordToSave.getId() == null && recordToSave.getFilename() != null) {
                recordToSave.setId(recordToSave.getFilename());
              } else if (recordToSave.getId() == null) {
                recordToSave.setId(UUID.randomUUID().toString());
              }
              if (recordToSave.getFilename() == null && recordToSave.getId() != null) {
                recordToSave.setFilename(recordToSave.getId());
              }
              return recordToSave;
            });
    lenient().when(fileRecordRepository.findByFilename(anyString())).thenReturn(Optional.empty());
    lenient().when(fileRecordRepository.findByToken(anyString())).thenReturn(Optional.empty());
    lenient()
        .when(
            fileRecordRepository.findByOwnerIdAndTagsContaining(
                anyString(), anyString(), any(Pageable.class)))
        .thenReturn(Page.empty());
    lenient()
        .when(fileRecordRepository.findByOwnerId(anyString(), any(Pageable.class)))
        .thenReturn(Page.empty());
    lenient()
        .when(fileRecordRepository.findByVisibility(anyString(), any(Pageable.class)))
        .thenReturn(Page.empty());
    lenient()
        .when(
            fileRecordRepository.findByVisibilityAndTagsContaining(
                anyString(), anyString(), any(Pageable.class)))
        .thenReturn(Page.empty());

    lenient()
        .when(fileMapper.fromEntity(any(FileRecord.class)))
        .thenAnswer(
            invocation -> {
              FileRecord fr = invocation.getArgument(0);
              if (fr == null) return null;
              return new FileResponse(
                  fr.getId(),
                  fr.getOriginalFilename(),
                  fr.getVisibility(),
                  fr.getTags(),
                  fr.getUploadDate(),
                  fr.getContentType(),
                  fr.getSize(),
                  "/api/v1/files/download/" + fr.getToken());
            });
  }

  @Test
  void uploadFile_whenFilenameExistsForUser_shouldThrowFileAlreadyExistsException() {
    // Simulate MongoWriteException due to filename conflict from GridFsHelper
    String expectedMessageContent =
        "E11000 duplicate key error collection: test.fs.files index: owner_filename_idx dup key: { metadata.ownerId: \""
            + testUserId
            + "\", metadata.originalFilename: \""
            + defaultUploadRequest.filename()
            + "\" }";
    try {
      when(gridFsHelper.storeAndHash(
              eq(mockMultipartFile),
              argThat(
                  record -> defaultUploadRequest.filename().equals(record.getOriginalFilename()))))
          .thenThrow(
              new MongoWriteException(
                  new WriteError(
                      11000,
                      "owner_filename_idx conflict",
                      new BsonDocument("details", new BsonString(expectedMessageContent))),
                  new ServerAddress(),
                  Collections.emptySet()));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }

    FileAlreadyExistsException ex =
        assertThrows(
            FileAlreadyExistsException.class,
            () -> fileService.uploadFile(testUserId, mockMultipartFile, defaultUploadRequest));

    String expectedDisplayMessage =
        "Filename '" + defaultUploadRequest.filename() + "' already exists for this user.";
    assertEquals(expectedDisplayMessage, ex.getMessage());
    verify(fileRecordRepository, never()).save(any());
  }

  @Test
  void uploadFile_whenContentExistsForUser_shouldThrowFileAlreadyExistsException()
      throws IOException, NoSuchAlgorithmException {
    FileUploadRequest contentCheckRequest =
        new FileUploadRequest("new_unique_filename.txt", Visibility.PRIVATE, List.of("content"));
    FileRecord recordAsBuiltByMock =
        fileMetadataBuilder.build(contentCheckRequest, testUserId, mockMultipartFile);
    String sha256FromBuilder = recordAsBuiltByMock.getSha256();

    String expectedMongoErrorMessagePart =
        "owner_sha256_idx dup key: { metadata.sha256: \"" + sha256FromBuilder + "\" }";
    when(gridFsHelper.storeAndHash(
            eq(mockMultipartFile), argThat(record -> sha256FromBuilder.equals(record.getSha256()))))
        .thenThrow(
            new MongoWriteException(
                new WriteError(
                    11000,
                    "owner_sha256_idx conflict",
                    new BsonDocument("details", new BsonString(expectedMongoErrorMessagePart))),
                new ServerAddress(),
                Collections.emptySet()));

    FileAlreadyExistsException exception =
        assertThrows(
            FileAlreadyExistsException.class,
            () -> fileService.uploadFile(testUserId, mockMultipartFile, contentCheckRequest));

    assertTrue(
        exception
            .getMessage()
            .contains(
                "Content with hash '" + sha256FromBuilder + "' already exists for this user."));
    verify(fileRecordRepository, never()).save(any());
  }

  @Test
  void uploadFile_whenFilenameAndContentAreUnique_shouldSucceed()
      throws IOException, NoSuchAlgorithmException {
    FileRecord recordAsBuiltByMock =
        fileMetadataBuilder.build(defaultUploadRequest, testUserId, mockMultipartFile);

    FileResponse response =
        fileService.uploadFile(testUserId, mockMultipartFile, defaultUploadRequest);

    assertNotNull(response);
    assertTrue(isValidUUID(response.id()), "Response ID should be a valid system UUID");
    assertEquals(
        defaultUploadRequest.filename(),
        response.filename(),
        "Response filename should match original request");
    assertEquals(defaultUploadRequest.visibility(), response.visibility());
    assertEquals(
        defaultUploadRequest.tags().stream().map(String::toLowerCase).collect(Collectors.toList()),
        response.tags());
    assertNotNull(response.uploadDate());
    assertEquals(mockMultipartFile.getContentType(), response.contentType());
    assertEquals(mockMultipartFile.getSize(), response.size());
    assertNotNull(response.downloadLink());
    assertTrue(
        response.downloadLink().substring("/api/v1/files/download/".length()).length() > 0,
        "Token should be present in download link");

    ArgumentCaptor<FileRecord> recordForStoreCaptor = ArgumentCaptor.forClass(FileRecord.class);
    verify(gridFsHelper).storeAndHash(eq(mockMultipartFile), recordForStoreCaptor.capture());
    FileRecord capturedRecordForStore = recordForStoreCaptor.getValue();
    assertEquals(defaultUploadRequest.filename(), capturedRecordForStore.getOriginalFilename());
    assertEquals(testUserId, capturedRecordForStore.getOwnerId());
    assertEquals(recordAsBuiltByMock.getSha256(), capturedRecordForStore.getSha256());

    verify(fileMapper).fromEntity(capturedRecordForStore);
  }

  @Test
  void uploadFile_whenTooManyTags_shouldThrowIllegalArgumentException() {
    List<String> tooManyTags = List.of("tag1", "tag2", "tag3", "tag4", "tag5", "tag6");
    FileUploadRequest requestWithTooManyTags =
        new FileUploadRequest(defaultUploadRequest.filename(), Visibility.PRIVATE, tooManyTags);

    InvalidRequestArgumentException exception =
        assertThrows(
            InvalidRequestArgumentException.class,
            () -> {
              fileService.uploadFile(testUserId, mockMultipartFile, requestWithTooManyTags);
            });

    assertEquals("A maximum of 5 tags are allowed.", exception.getMessage());
  }

  @Test
  void uploadFile_whenFileIsEmpty_shouldThrowIllegalArgumentException() {
    // Arrange
    when(mockMultipartFile.isEmpty()).thenReturn(true); // Override lenient setUp mock

    FileUploadRequest localFileUploadRequest =
        new FileUploadRequest("empty_file.txt", Visibility.PRIVATE, Collections.emptyList());

    // Act & Assert
    InvalidRequestArgumentException exception =
        assertThrows(
            InvalidRequestArgumentException.class,
            () -> fileService.uploadFile(testUserId, mockMultipartFile, localFileUploadRequest));

    assertEquals("File is empty", exception.getMessage());

    // Verify no interactions with helpers or repository if file is empty
    verify(fileMetadataBuilder, never()).build(any(), any(), any());
    try {
      verify(gridFsHelper, never()).storeAndHash(any(), any());
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
    verify(fileRecordRepository, never()).save(any());
  }

  @Test
  void downloadFile_whenTokenDoesNotExist_shouldThrowException() throws Exception {
    // Use the testToken field directly, FileUploadRequest does not have a token.
    when(fileRecordRepository.findByToken(eq(testToken))).thenReturn(Optional.empty());

    ResourceNotFoundException exception =
        assertThrows(ResourceNotFoundException.class, () -> fileService.downloadFile(testToken));
    assertTrue(exception.getMessage().contains("File not found for token: " + testToken));

    verify(fileRecordRepository).findByToken(eq(testToken));
    verify(gridFsTemplate, never()).findOne(any(Query.class));
    verify(gridFsTemplate, never()).getResource(any(GridFSFile.class));
  }

  @Test
  void updateFileDetails_whenFileExistsAndUserOwnsItAndNewNameIsValid_shouldSucceed() {
    String systemFileId = testFileId;
    String oldOriginalFilename = "old_document.txt";
    String newOriginalFilename = newFilename;
    FileUpdateRequest localUpdateRequest = new FileUpdateRequest(newOriginalFilename);

    FileRecord initialRecord =
        FileRecord.builder()
            .id(systemFileId)
            .filename(systemFileId)
            .originalFilename(oldOriginalFilename)
            .ownerId(testUserId)
            .visibility(Visibility.PRIVATE)
            .token("some-token-for-update")
            .uploadDate(new Date())
            .contentType("text/plain")
            .size(123L)
            .tags(Collections.singletonList("initial"))
            .build();

    when(fileRecordRepository.findByFilename(eq(systemFileId)))
        .thenReturn(Optional.of(initialRecord));
    ArgumentCaptor<FileRecord> recordSaveCaptor = ArgumentCaptor.forClass(FileRecord.class);
    when(fileRecordRepository.save(recordSaveCaptor.capture()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    FileResponse expectedResponseAfterUpdate =
        new FileResponse(
            systemFileId,
            newOriginalFilename,
            initialRecord.getVisibility(),
            initialRecord.getTags(),
            initialRecord.getUploadDate(),
            initialRecord.getContentType(),
            initialRecord.getSize(),
            "/api/v1/files/download/" + initialRecord.getToken());
    when(fileMapper.fromEntity(
            argThat(record -> newOriginalFilename.equals(record.getOriginalFilename()))))
        .thenReturn(expectedResponseAfterUpdate);

    FileResponse actualResponse =
        fileService.updateFileDetails(testUserId, systemFileId, localUpdateRequest);

    assertNotNull(actualResponse);
    assertEquals(expectedResponseAfterUpdate, actualResponse);
    assertEquals(newOriginalFilename, actualResponse.filename());

    verify(fileRecordRepository).findByFilename(eq(systemFileId));
    verify(fileRecordRepository).save(recordSaveCaptor.capture());
    verify(fileMapper).fromEntity(recordSaveCaptor.getValue());
  }

  @Test
  void updateFileDetails_whenFileDoesNotExistById_shouldThrowResourceNotFoundException() {
    String nonExistentFileId = testFileId; // Use a consistent ID for clarity
    FileUpdateRequest localUpdateRequest = new FileUpdateRequest(newFilename);
    when(fileRecordRepository.findByFilename(eq(nonExistentFileId))).thenReturn(Optional.empty());

    ResourceNotFoundException ex =
        assertThrows(
            ResourceNotFoundException.class,
            () -> fileService.updateFileDetails(testUserId, nonExistentFileId, localUpdateRequest));

    assertEquals("File not found with id: " + nonExistentFileId, ex.getMessage());
    verify(fileRecordRepository).findByFilename(eq(nonExistentFileId));
    verify(fileRecordRepository, never()).save(any(FileRecord.class));
  }

  @Test
  void updateFileDetails_whenNewFilenameConflicts_shouldThrowException() {
    String conflictingFilename = "i_already_exist.txt";
    FileUpdateRequest localUpdateRequest = new FileUpdateRequest(conflictingFilename);
    String systemFileId = testFileId;

    FileRecord initialRecord =
        FileRecord.builder()
            .id(systemFileId)
            .filename(systemFileId)
            .originalFilename("old_name.txt")
            .ownerId(testUserId)
            .visibility(Visibility.PRIVATE)
            .build();

    when(fileRecordRepository.findByFilename(eq(systemFileId)))
        .thenReturn(Optional.of(initialRecord));
    when(fileRecordRepository.save(any(FileRecord.class)))
        .thenThrow(
            new org.springframework.dao.DuplicateKeyException(
                "E11000 duplicate key error collection: fs.files index: owner_filename_idx dup key: { ... }"));

    FileAlreadyExistsException exception =
        assertThrows(
            FileAlreadyExistsException.class,
            () -> fileService.updateFileDetails(testUserId, systemFileId, localUpdateRequest));

    assertTrue(
        exception
            .getMessage()
            .contains("Filename '" + conflictingFilename + "' already exists for this user"));
    verify(fileRecordRepository).findByFilename(eq(systemFileId));
    verify(fileRecordRepository).save(any(FileRecord.class));
  }

  @Test
  void updateFileDetails_whenUpdateOperationReportsNoModification_shouldThrowException() {
    String fileId = testFileId;
    FileRecord record =
        FileRecord.builder()
            .id(fileId)
            .filename(fileId)
            .ownerId(testUserId)
            .originalFilename("no_change.txt")
            .build();
    when(fileRecordRepository.findByFilename(eq(fileId))).thenReturn(Optional.of(record));
    FileUpdateRequest localUpdateRequest = new FileUpdateRequest("some_new_name.txt");
    when(fileRecordRepository.save(any(FileRecord.class)))
        .thenThrow(new StorageException("DB did not acknowledge update"));

    StorageException ex =
        assertThrows(
            StorageException.class,
            () -> fileService.updateFileDetails(testUserId, fileId, localUpdateRequest));
    assertEquals("DB did not acknowledge update", ex.getMessage());

    verify(fileRecordRepository).findByFilename(eq(fileId));
    verify(fileRecordRepository).save(any(FileRecord.class));
  }

  @Test
  void deleteFile_whenFileExistsAndUserOwnsIt_shouldDeleteFile() {
    String systemFileId = testFileId;
    FileRecord recordToDelete =
        FileRecord.builder()
            .id(systemFileId)
            .filename(systemFileId)
            .ownerId(testUserId)
            .originalFilename("user_file_to_delete.txt")
            .build();

    when(fileRecordRepository.findByFilename(eq(systemFileId)))
        .thenReturn(Optional.of(recordToDelete));
    // Arrange: mock GridFSFile with known ObjectId
    ObjectId mockObjectId = new ObjectId("507f1f77bcf86cd799439011");
    GridFSFile mockGridFSFile = mock(GridFSFile.class);
    when(mockGridFSFile.getObjectId()).thenReturn(mockObjectId);
    when(gridFsTemplate.findOne(any(Query.class))).thenReturn(mockGridFSFile);

    assertDoesNotThrow(() -> fileService.deleteFile(testUserId, systemFileId));

    verify(fileRecordRepository).findByFilename(eq(systemFileId));
    // Verify GridFS findOne was called with the correct query (based on record.getFilename())
    Query expectedGridFsFindQuery =
        Query.query(Criteria.where("filename").is(recordToDelete.getFilename()));
    verify(gridFsTemplate).findOne(eq(expectedGridFsFindQuery));
    // Verify GridFS delete was called with the correct query (based on _id)
    Query expectedGridFsDeleteQuery = Query.query(Criteria.where("_id").is(mockObjectId));
    verify(gridFsTemplate).delete(eq(expectedGridFsDeleteQuery));
    verify(fileRecordRepository).delete(eq(recordToDelete));
  }

  @Test
  void deleteFile_InvalidOrNonExistentFileId_ShouldThrowResourceNotFoundException() {
    String invalidOrNonExistentFileId = "this-is-not-a-uuid-and-should-not-be-found";

    when(fileRecordRepository.findByFilename(eq(invalidOrNonExistentFileId)))
        .thenReturn(Optional.empty());

    ResourceNotFoundException ex =
        assertThrows(
            ResourceNotFoundException.class,
            () -> fileService.deleteFile(testUserId, invalidOrNonExistentFileId));
    assertEquals("File not found with id: " + invalidOrNonExistentFileId, ex.getMessage());

    verify(fileRecordRepository).findByFilename(eq(invalidOrNonExistentFileId));
    verify(gridFsTemplate, never()).delete(any(Query.class));
    verify(fileRecordRepository, never()).delete(any(FileRecord.class));
  }

  @Test
  void deleteFile_UnauthorizedUser_ShouldThrowException() {
    String systemFileId = testFileId;
    String actualOwnerId = "anotherUser123";
    String attackerUserId = testUserId;
    FileRecord recordOwnedByOther =
        FileRecord.builder()
            .id(systemFileId)
            .filename(systemFileId)
            .ownerId(actualOwnerId)
            .originalFilename("secret.dat")
            .build();

    when(fileRecordRepository.findByFilename(eq(systemFileId)))
        .thenReturn(Optional.of(recordOwnedByOther));

    UnauthorizedOperationException ex =
        assertThrows(
            UnauthorizedOperationException.class,
            () -> {
              fileService.deleteFile(attackerUserId, systemFileId);
            });

    assertEquals(
        "User '" + attackerUserId + "' not authorized to delete fileId: " + systemFileId,
        ex.getMessage());

    verify(fileRecordRepository).findByFilename(eq(systemFileId));
    verify(gridFsTemplate, never()).delete(any(Query.class));
    verify(fileRecordRepository, never()).delete(any(FileRecord.class));
  }

  @Test
  void deleteFile_FileRecordOwnerIdIsNull_ShouldThrowUnauthorizedOperationException() {
    String systemFileId = testFileId;
    FileRecord recordWithNullOwner =
        FileRecord.builder()
            .id(systemFileId)
            .filename(systemFileId)
            .ownerId(null)
            .originalFilename("data.bin")
            .build();

    when(fileRecordRepository.findByFilename(eq(systemFileId)))
        .thenReturn(Optional.of(recordWithNullOwner));

    UnauthorizedOperationException ex =
        assertThrows(
            UnauthorizedOperationException.class,
            () -> {
              fileService.deleteFile(testUserId, systemFileId);
            });
    // Message check needs to be consistent with how service handles this specific scenario
    assertEquals(
        "User '" + testUserId + "' not authorized to delete fileId: " + systemFileId,
        ex.getMessage());

    verify(fileRecordRepository).findByFilename(eq(systemFileId));
    verify(gridFsTemplate, never()).delete(any(Query.class));
    verify(fileRecordRepository, never()).deleteById(anyString());
    verify(fileRecordRepository, never()).delete(any(FileRecord.class));
  }

  @Test
  void listFiles_whenNoUserIdAndNoTag_shouldListPublicFilesWithDefaultSort() {
    // Arrange
    int pageNum = 0;
    int pageSize = 10;
    // Service default sort is uploadDate ASC if sortBy is null
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, "uploadDate"));

    FileRecord publicRecord1 =
        FileRecord.builder()
            .id("id1")
            .originalFilename("public1.txt")
            .visibility(Visibility.PUBLIC)
            .build();
    FileResponse publicResponse1 =
        new FileResponse(
            "id1",
            "public1.txt",
            Visibility.PUBLIC,
            Collections.emptyList(),
            new Date(),
            "text/plain",
            100L,
            "link1");

    List<FileRecord> records = List.of(publicRecord1);
    Page<FileRecord> recordPage = new PageImpl<>(records, expectedPageable, records.size());

    // 1. Mock repository to return a page of public FileRecords
    when(fileRecordRepository.findByVisibility(eq("PUBLIC"), eq(expectedPageable)))
        .thenReturn(recordPage);

    // 2. Mock mapper to convert FileRecord to FileResponse (already leniently mocked in setUp, but
    // can be specific)
    when(fileMapper.fromEntity(publicRecord1)).thenReturn(publicResponse1);

    // Act
    Page<FileResponse> resultPage =
        fileService.listFiles(null, null, null, "asc", pageNum, pageSize);

    // Assert
    assertNotNull(resultPage);
    assertEquals(1, resultPage.getTotalElements());
    assertEquals(1, resultPage.getContent().size());
    assertEquals(publicResponse1, resultPage.getContent().get(0));

    // Verify repository call
    verify(fileRecordRepository).findByVisibility(eq("PUBLIC"), eq(expectedPageable));
    verify(fileMapper).fromEntity(publicRecord1);
  }

  @Test
  void listFiles_whenUserIdProvided_shouldListUserFiles() {
    // Arrange
    int pageNum = 0;
    int pageSize = 5;
    String sortByApi = "filename"; // Service maps this to "originalFilename"
    String sortDir = "asc";
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, "originalFilename"));

    String systemIdUser1 = UUID.randomUUID().toString();
    String originalFilenameUser1 = "userFileA.doc";
    FileRecord recordUser1 =
        FileRecord.builder()
            .id(systemIdUser1)
            .filename(systemIdUser1)
            .originalFilename(originalFilenameUser1)
            .ownerId(testUserId)
            .visibility(Visibility.PRIVATE)
            .uploadDate(new Date(System.currentTimeMillis() - 20000))
            .contentType("application/msword")
            .tags(List.of("user_tag"))
            .token("userToken1")
            .size(500L)
            .build();
    FileResponse responseUser1 =
        new FileResponse(
            systemIdUser1,
            originalFilenameUser1,
            Visibility.PRIVATE,
            recordUser1.getTags(),
            recordUser1.getUploadDate(),
            recordUser1.getContentType(),
            recordUser1.getSize(),
            "/api/v1/files/download/" + recordUser1.getToken());

    String systemIdUser2 = UUID.randomUUID().toString();
    String originalFilenameUser2 = "userFileB.pdf";
    FileRecord recordUser2 =
        FileRecord.builder()
            .id(systemIdUser2)
            .filename(systemIdUser2)
            .originalFilename(originalFilenameUser2)
            .ownerId(testUserId)
            .visibility(Visibility.PUBLIC)
            .uploadDate(new Date(System.currentTimeMillis() - 15000))
            .contentType("application/pdf")
            .tags(List.of("user_tag", "shared"))
            .token("userToken2")
            .size(1500L)
            .build();
    FileResponse responseUser2 =
        new FileResponse(
            systemIdUser2,
            originalFilenameUser2,
            Visibility.PUBLIC,
            recordUser2.getTags(),
            recordUser2.getUploadDate(),
            recordUser2.getContentType(),
            recordUser2.getSize(),
            "/api/v1/files/download/" + recordUser2.getToken());

    List<FileRecord> userRecords = List.of(recordUser1, recordUser2);
    Page<FileRecord> recordPage = new PageImpl<>(userRecords, expectedPageable, userRecords.size());

    // Mock repository to return the page of user's FileRecords
    when(fileRecordRepository.findByOwnerId(eq(testUserId), eq(expectedPageable)))
        .thenReturn(recordPage);

    // Mock mapper for each record
    when(fileMapper.fromEntity(eq(recordUser1))).thenReturn(responseUser1);
    when(fileMapper.fromEntity(eq(recordUser2))).thenReturn(responseUser2);

    // Act
    Page<FileResponse> resultPage =
        fileService.listFiles(testUserId, null, sortByApi, sortDir, pageNum, pageSize);

    // Assert
    assertNotNull(resultPage);
    assertEquals(2, resultPage.getTotalElements());
    assertEquals(2, resultPage.getContent().size());

    // Assuming order is preserved or matches the sort order in expectedPageable
    assertEquals(responseUser1, resultPage.getContent().get(0));
    assertEquals(responseUser2, resultPage.getContent().get(1));

    // Verify repository call with correct Pageable
    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(fileRecordRepository).findByOwnerId(eq(testUserId), pageableCaptor.capture());
    Pageable capturedPageable = pageableCaptor.getValue();
    assertEquals(pageNum, capturedPageable.getPageNumber());
    assertEquals(pageSize, capturedPageable.getPageSize());
    assertEquals(Sort.by(Sort.Direction.ASC, "originalFilename"), capturedPageable.getSort());

    verify(fileMapper).fromEntity(eq(recordUser1));
    verify(fileMapper).fromEntity(eq(recordUser2));
  }

  @Test
  void listFiles_whenTagProvided_shouldFilterByTagCaseInsensitively() {
    // Arrange
    int pageNum = 0;
    int pageSize = 10;
    String filterTagApi = "Work"; // Mixed case for API
    String filterTagDb = "work"; // Lowercase for DB query
    String sortByApi = "uploadDate"; // Example sort
    String sortDir = "desc";
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "uploadDate"));

    FileRecord taggedRecord =
        FileRecord.builder()
            .id("taggedId1")
            .filename("taggedSystemId1")
            .originalFilename("work_document.docx")
            .visibility(Visibility.PUBLIC)
            .tags(List.of("personal", filterTagDb)) // Stored as lowercase
            .ownerId("owner1")
            .uploadDate(new Date())
            .contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            .token("tokenTagged1")
            .size(2500L)
            .build();
    FileResponse taggedResponse =
        new FileResponse(
            "taggedId1",
            "work_document.docx",
            Visibility.PUBLIC,
            List.of("personal", filterTagDb),
            taggedRecord.getUploadDate(),
            taggedRecord.getContentType(),
            taggedRecord.getSize(),
            "/api/v1/files/download/" + taggedRecord.getToken());

    List<FileRecord> records = List.of(taggedRecord);
    Page<FileRecord> recordPage = new PageImpl<>(records, expectedPageable, records.size());

    // Mock repository to return a page of PUBLIC FileRecords matching the lowercase tag
    when(fileRecordRepository.findByVisibilityAndTagsContaining(
            eq("PUBLIC"), eq(filterTagDb), eq(expectedPageable)))
        .thenReturn(recordPage);

    // Mock mapper
    when(fileMapper.fromEntity(eq(taggedRecord))).thenReturn(taggedResponse);

    // Act: userId is null, filterTagApi is "Work"
    Page<FileResponse> resultPage =
        fileService.listFiles(null, filterTagApi, sortByApi, sortDir, pageNum, pageSize);

    // Assert
    assertNotNull(resultPage);
    assertEquals(1, resultPage.getTotalElements());
    assertEquals(1, resultPage.getContent().size());
    assertEquals(taggedResponse, resultPage.getContent().get(0));

    // Verify repository call (service should convert filterTagApi to lowercase)
    verify(fileRecordRepository)
        .findByVisibilityAndTagsContaining(eq("PUBLIC"), eq(filterTagDb), eq(expectedPageable));
    verify(fileMapper).fromEntity(eq(taggedRecord));
  }

  @Test
  void listFiles_whenSortByFilenameAsc_shouldReturnSortedResults() {
    // Arrange
    int pageNum = 0;
    int pageSize = 10;
    String sortByApi = "filename"; // Service maps this to "originalFilename"
    String sortDir = "asc";
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, "originalFilename"));

    FileRecord recordA =
        FileRecord.builder()
            .id("idA")
            .filename("systemIdA") // System filename
            .originalFilename("alpha.txt") // User-facing, for sorting
            .visibility(Visibility.PUBLIC)
            .ownerId("userX") // Some owner
            .uploadDate(new Date())
            .contentType("text/plain")
            .tags(Collections.emptyList())
            .token("tokenA")
            .size(10L)
            .build();
    FileResponse responseA =
        new FileResponse(
            "idA",
            "alpha.txt",
            Visibility.PUBLIC,
            Collections.emptyList(),
            recordA.getUploadDate(),
            "text/plain",
            10L,
            "/api/v1/files/download/tokenA");

    FileRecord recordG =
        FileRecord.builder()
            .id("idG")
            .filename("systemIdG")
            .originalFilename("gamma.txt") // User-facing, for sorting
            .visibility(Visibility.PUBLIC)
            .ownerId("userY")
            .uploadDate(new Date())
            .contentType("text/plain")
            .tags(Collections.emptyList())
            .token("tokenG")
            .size(20L)
            .build();
    FileResponse responseG =
        new FileResponse(
            "idG",
            "gamma.txt",
            Visibility.PUBLIC,
            Collections.emptyList(),
            recordG.getUploadDate(),
            "text/plain",
            20L,
            "/api/v1/files/download/tokenG");

    // Records sorted by originalFilename ASC for the public listing
    List<FileRecord> publicRecords = List.of(recordA, recordG);
    Page<FileRecord> recordPage =
        new PageImpl<>(publicRecords, expectedPageable, publicRecords.size());

    // Mock repository to return the sorted page of PUBLIC FileRecords
    when(fileRecordRepository.findByVisibility(eq("PUBLIC"), eq(expectedPageable)))
        .thenReturn(recordPage);

    // Mock mapper for each record
    when(fileMapper.fromEntity(eq(recordA))).thenReturn(responseA);
    when(fileMapper.fromEntity(eq(recordG))).thenReturn(responseG);

    // Act: userId and filterTag are null, sorting by filename ASC
    Page<FileResponse> resultPage =
        fileService.listFiles(null, null, sortByApi, sortDir, pageNum, pageSize);

    // Assert
    assertNotNull(resultPage);
    assertEquals(2, resultPage.getTotalElements());
    assertEquals(2, resultPage.getContent().size());
    assertEquals(responseA, resultPage.getContent().get(0)); // alpha.txt first
    assertEquals(responseG, resultPage.getContent().get(1)); // gamma.txt second

    // Verify repository call
    verify(fileRecordRepository).findByVisibility(eq("PUBLIC"), eq(expectedPageable));
    verify(fileMapper).fromEntity(eq(recordA));
    verify(fileMapper).fromEntity(eq(recordG));
  }

  @Test
  void listFiles_whenSortBySizeDesc_shouldReturnSortedResults() {
    // Arrange
    int pageNum = 0;
    int pageSize = 10;
    String sortByApi = "size"; // Service maps this to "size"
    String sortDir = "desc";
    // mapSortField in FileServiceImpl maps "size" to "size" (FileRecord.size)
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "size"));

    FileRecord recordL =
        FileRecord.builder()
            .id("idL")
            .originalFilename("large.doc")
            .visibility(Visibility.PUBLIC)
            .size(5000L)
            .build();
    FileRecord recordM =
        FileRecord.builder()
            .id("idM")
            .originalFilename("medium.pdf")
            .visibility(Visibility.PUBLIC)
            .size(500L)
            .build();
    FileRecord recordS =
        FileRecord.builder()
            .id("idS")
            .originalFilename("small.txt")
            .visibility(Visibility.PUBLIC)
            .size(50L)
            .build();

    FileResponse responseL =
        new FileResponse(
            "idL",
            "large.doc",
            Visibility.PUBLIC,
            Collections.emptyList(),
            new Date(),
            "app/doc",
            5000L,
            "linkL");
    FileResponse responseM =
        new FileResponse(
            "idM",
            "medium.pdf",
            Visibility.PUBLIC,
            Collections.emptyList(),
            new Date(),
            "app/pdf",
            500L,
            "linkM");
    FileResponse responseS =
        new FileResponse(
            "idS",
            "small.txt",
            Visibility.PUBLIC,
            Collections.emptyList(),
            new Date(),
            "text/plain",
            50L,
            "linkS");

    // Records sorted by size DESC
    List<FileRecord> records = List.of(recordL, recordM, recordS);
    Page<FileRecord> recordPage = new PageImpl<>(records, expectedPageable, records.size());

    // 1. Mock repository to return the sorted page of public FileRecords
    when(fileRecordRepository.findByVisibility(eq("PUBLIC"), eq(expectedPageable)))
        .thenReturn(recordPage);

    // 2. Mock mapper for each record
    when(fileMapper.fromEntity(recordL)).thenReturn(responseL);
    when(fileMapper.fromEntity(recordM)).thenReturn(responseM);
    when(fileMapper.fromEntity(recordS)).thenReturn(responseS);

    // Act: userId and filterTag are null
    Page<FileResponse> resultPage =
        fileService.listFiles(null, null, sortByApi, sortDir, pageNum, pageSize);

    // Assert
    assertNotNull(resultPage);
    assertEquals(3, resultPage.getTotalElements());
    assertEquals(3, resultPage.getContent().size());
    assertEquals(responseL, resultPage.getContent().get(0));
    assertEquals(responseM, resultPage.getContent().get(1));
    assertEquals(responseS, resultPage.getContent().get(2));

    // Verify repository call
    verify(fileRecordRepository).findByVisibility(eq("PUBLIC"), eq(expectedPageable));
    verify(fileMapper).fromEntity(recordL);
    verify(fileMapper).fromEntity(recordM);
    verify(fileMapper).fromEntity(recordS);
  }

  @Test
  void listFiles_whenNoFilesMatchCriteria_shouldReturnEmptyPage() {
    // Arrange
    int pageNum = 0;
    int pageSize = 10;
    String filterTagApi = "nonexistenttag";
    String filterTagDb = "nonexistenttag"; // Already lowercase, or service handles it
    String sortByApi = "uploadDate";
    String sortDir = "desc";
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "uploadDate"));

    // Empty list of records
    List<FileRecord> emptyRecordsList = Collections.emptyList();
    Page<FileRecord> emptyRecordPage =
        new PageImpl<>(emptyRecordsList, expectedPageable, 0); // Total elements is 0

    // Mock repository to return an empty page for the given criteria
    // Assuming public files are being queried as userId is null in the service call below
    when(fileRecordRepository.findByVisibilityAndTagsContaining(
            eq("PUBLIC"), eq(filterTagDb), eq(expectedPageable)))
        .thenReturn(emptyRecordPage);

    // fileMapper.fromEntity will not be called if the page content is empty.

    // Act
    Page<FileResponse> resultPage =
        fileService.listFiles(null, filterTagApi, sortByApi, sortDir, pageNum, pageSize);

    // Assert
    assertNotNull(resultPage);
    assertEquals(0, resultPage.getTotalElements());
    assertTrue(resultPage.getContent().isEmpty());
    assertEquals(pageNum, resultPage.getNumber());
    assertEquals(pageSize, resultPage.getSize()); // Pageable's size requested

    // Verify repository call
    verify(fileRecordRepository)
        .findByVisibilityAndTagsContaining(eq("PUBLIC"), eq(filterTagDb), eq(expectedPageable));
    verify(fileMapper, never()).fromEntity(any(FileRecord.class)); // Mapper should not be called
  }

  @Test
  void listFiles_whenInvalidSortByField_shouldThrowIllegalArgumentException() {
    // Arrange
    String userId = "testUser";
    String invalidSortField = "nonExistentSortField";
    // Other params like filterTag, sortDir, page, size are not critical for this specific exception
    // path.

    // Act & Assert
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> fileService.listFiles(userId, null, invalidSortField, "asc", 0, 10));

    assertTrue(exception.getMessage().contains("Invalid sortBy field: " + invalidSortField));

    // Verify no repository interactions occurred
    verify(fileRecordRepository, never()).findByOwnerId(anyString(), any(Pageable.class));
    verify(fileRecordRepository, never()).findByVisibility(anyString(), any(Pageable.class));
    verify(fileRecordRepository, never())
        .findByVisibilityAndTagsContaining(anyString(), anyString(), any(Pageable.class));
  }

  @Test
  void updateFileDetails_whenUserNotOwner_shouldThrowUnauthorizedOperationException() {
    String fileId = testFileId;
    String ownerId = "ownerUser";
    String attackerId = testUserId; // testUserId is the attacker in this context
    FileRecord record =
        FileRecord.builder()
            .id(fileId)
            .filename(fileId)
            .ownerId(ownerId)
            .originalFilename("owned.txt")
            .build();
    when(fileRecordRepository.findByFilename(eq(fileId))).thenReturn(Optional.of(record));
    FileUpdateRequest localUpdateRequest = new FileUpdateRequest("attempted_update.txt");

    UnauthorizedOperationException ex =
        assertThrows(
            UnauthorizedOperationException.class,
            () -> fileService.updateFileDetails(attackerId, fileId, localUpdateRequest));
    assertEquals(
        "User '" + attackerId + "' not authorized to update fileId: " + fileId, ex.getMessage());
    verify(fileRecordRepository).findByFilename(eq(fileId));
    verify(fileRecordRepository, never()).save(any(FileRecord.class));
  }

  @Test
  void uploadFile_Success_WithGivenFilename() throws Exception {
    // Arrange
    String userId = testUserId;
    String requestFilename = "user_req_fname.txt";
    FileUploadRequest uploadRequest =
        new FileUploadRequest(requestFilename, Visibility.PUBLIC, List.of("t1", "t2"));

    // Mock FileMetadataBuilder
    FileRecord builtFileRecord =
        FileRecord.builder()
            .id(UUID.randomUUID().toString())
            .filename(UUID.randomUUID().toString())
            .originalFilename(requestFilename)
            .ownerId(userId)
            .visibility(uploadRequest.visibility())
            .tags(
                uploadRequest.tags().stream().map(String::toLowerCase).collect(Collectors.toList()))
            .contentType("text/plain")
            .size(100L)
            .uploadDate(new Date())
            .token(UUID.randomUUID().toString())
            .sha256("mockedSha256ForThisTest")
            .build();
    when(fileMetadataBuilder.build(eq(uploadRequest), eq(userId), eq(mockMultipartFile)))
        .thenReturn(builtFileRecord);

    // Mock GridFsHelper
    FileStorageResult storageResult =
        new FileStorageResult(
            new ObjectId(),
            "mockedSha256ForThisTest",
            "text/plain",
            100L,
            new Document("originalFilename", requestFilename));
    when(gridFsHelper.storeAndHash(eq(mockMultipartFile), eq(builtFileRecord)))
        .thenReturn(storageResult);

    // Mock FileMapper
    FileResponse expectedResponse =
        new FileResponse(
            builtFileRecord.getId(),
            requestFilename,
            Visibility.PUBLIC,
            List.of("t1", "t2"),
            builtFileRecord.getUploadDate(),
            "text/plain",
            100L,
            "/api/v1/files/download/" + builtFileRecord.getToken());
    when(fileMapper.fromEntity(
            argThat(
                record ->
                    record.getId().equals(builtFileRecord.getId())
                        && record.getSha256().equals(storageResult.sha256))))
        .thenReturn(expectedResponse);

    // Act
    FileResponse actualResponse = fileService.uploadFile(userId, mockMultipartFile, uploadRequest);

    // Assert
    assertNotNull(actualResponse);
    assertEquals(expectedResponse.id(), actualResponse.id());
    assertEquals(expectedResponse.filename(), actualResponse.filename());

    // Verify interactions
    verify(fileMetadataBuilder).build(eq(uploadRequest), eq(userId), eq(mockMultipartFile));
    verify(gridFsHelper).storeAndHash(eq(mockMultipartFile), eq(builtFileRecord));
    verify(fileRecordRepository, never()).save(any(FileRecord.class));
  }

  @Test
  void uploadFile_Success_UsesOriginalFilename_WhenRequestFilenameNull() throws Exception {
    // Arrange
    String userId = testUserId;
    String expectedOriginalFilename = "mp_original_name_" + UUID.randomUUID().toString() + ".txt";
    FileUploadRequest uploadRequestNoFilename =
        new FileUploadRequest(null, Visibility.PRIVATE, List.of("tagB"));

    // Test-specific mock for MultipartFile.getOriginalFilename()
    when(mockMultipartFile.getOriginalFilename()).thenReturn(expectedOriginalFilename);
    // Other MultipartFile mocks (isEmpty, getContentType, getSize) should rely on lenient setUp()
    // if defaults are fine.

    // Mock FileMetadataBuilder to use the MultipartFile's original name
    FileRecord builtFileRecord =
        FileRecord.builder()
            .id(UUID.randomUUID().toString())
            .filename(UUID.randomUUID().toString())
            .originalFilename(expectedOriginalFilename) // This should be picked up by the builder
            .ownerId(userId)
            .visibility(uploadRequestNoFilename.visibility())
            .tags(
                uploadRequestNoFilename.tags().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toList()))
            .contentType("text/plain") // Assuming default or test-specific
            .size(120L) // Assuming specific or rely on a mockMultipartFile.getSize() if needed
            .uploadDate(new Date())
            .token(UUID.randomUUID().toString())
            .sha256("mockedSha256ForNullFilenameTest")
            .build();
    when(fileMetadataBuilder.build(eq(uploadRequestNoFilename), eq(userId), eq(mockMultipartFile)))
        .thenReturn(builtFileRecord);

    // Mock GridFsHelper
    FileStorageResult storageResult =
        new FileStorageResult(
            new ObjectId(),
            "mockedSha256ForNullFilenameTest",
            "text/plain",
            120L,
            new Document("originalFilename", expectedOriginalFilename));
    when(gridFsHelper.storeAndHash(eq(mockMultipartFile), eq(builtFileRecord)))
        .thenReturn(storageResult);

    // Mock FileMapper
    FileResponse expectedResponse =
        new FileResponse(
            builtFileRecord.getId(),
            expectedOriginalFilename,
            uploadRequestNoFilename.visibility(),
            uploadRequestNoFilename.tags().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList()),
            builtFileRecord.getUploadDate(),
            "text/plain",
            120L,
            "/api/v1/files/download/" + builtFileRecord.getToken());
    when(fileMapper.fromEntity(
            argThat(
                record ->
                    record.getOriginalFilename().equals(expectedOriginalFilename)
                        && record.getSha256().equals(storageResult.sha256))))
        .thenReturn(expectedResponse);

    // Act
    FileResponse actualResponse =
        fileService.uploadFile(userId, mockMultipartFile, uploadRequestNoFilename);

    // Assert
    assertNotNull(actualResponse);
    assertEquals(
        expectedOriginalFilename,
        actualResponse.filename(),
        "Filename should be from MultipartFile");
    assertEquals(expectedResponse.id(), actualResponse.id());

    // Verify interactions
    verify(fileMetadataBuilder)
        .build(eq(uploadRequestNoFilename), eq(userId), eq(mockMultipartFile));
    verify(gridFsHelper).storeAndHash(eq(mockMultipartFile), eq(builtFileRecord));
  }

  @Test
  void uploadFile_Success_UsesOriginalFilename_WhenRequestFilenameBlank() throws Exception {
    // Arrange
    String userId = testUserId;
    String blankRequestFilename = "   "; // Blank filename in request
    String multipartOriginalFilename = "actual_multipart_name_" + UUID.randomUUID() + ".png";

    FileUploadRequest uploadRequestWithBlankFilename =
        new FileUploadRequest(blankRequestFilename, Visibility.PUBLIC, List.of("image"));

    // Specific MultipartFile mocks if different from setUp or needing multiple calls:
    when(mockMultipartFile.getOriginalFilename()).thenReturn(multipartOriginalFilename);
    // Other MultipartFile mocks (isEmpty, getContentType, getSize) should rely on lenient setUp().

    // Mock FileMetadataBuilder - it should use the blankRequestFilename from the request
    FileRecord builtFileRecord =
        FileRecord.builder()
            .id(UUID.randomUUID().toString())
            .filename(UUID.randomUUID().toString())
            .originalFilename(blankRequestFilename) // Builder logic prioritizes request filename
            .ownerId(userId)
            .visibility(uploadRequestWithBlankFilename.visibility())
            .tags(
                uploadRequestWithBlankFilename.tags().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toList()))
            .contentType("image/png") // Assume specific for test or rely on setUp
            .size(130L) // Assume specific or rely on setUp
            .uploadDate(new Date())
            .token(UUID.randomUUID().toString())
            .sha256("mockedSha256ForBlankFilenameTest")
            .build();
    when(fileMetadataBuilder.build(
            eq(uploadRequestWithBlankFilename), eq(userId), eq(mockMultipartFile)))
        .thenReturn(builtFileRecord);

    // Mock GridFsHelper
    FileStorageResult storageResult =
        new FileStorageResult(
            new ObjectId(),
            "mockedSha256ForBlankFilenameTest",
            "image/png",
            130L,
            new Document("originalFilename", blankRequestFilename));
    when(gridFsHelper.storeAndHash(eq(mockMultipartFile), eq(builtFileRecord)))
        .thenReturn(storageResult);

    // Mock FileMapper
    FileResponse expectedResponse =
        new FileResponse(
            builtFileRecord.getId(),
            blankRequestFilename,
            uploadRequestWithBlankFilename.visibility(),
            uploadRequestWithBlankFilename.tags().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList()),
            builtFileRecord.getUploadDate(),
            "image/png",
            130L,
            "/api/v1/files/download/" + builtFileRecord.getToken());
    when(fileMapper.fromEntity(
            argThat(
                record ->
                    record.getOriginalFilename().equals(blankRequestFilename)
                        && record.getSha256().equals(storageResult.sha256))))
        .thenReturn(expectedResponse);

    // Act
    FileResponse actualResponse =
        fileService.uploadFile(userId, mockMultipartFile, uploadRequestWithBlankFilename);

    // Assert
    assertNotNull(actualResponse);
    assertEquals(
        blankRequestFilename,
        actualResponse.filename(),
        "Filename should be the blank one from the request");
    assertEquals(expectedResponse.id(), actualResponse.id());

    // Verify interactions
    verify(fileMetadataBuilder)
        .build(eq(uploadRequestWithBlankFilename), eq(userId), eq(mockMultipartFile));
    verify(gridFsHelper).storeAndHash(eq(mockMultipartFile), eq(builtFileRecord));
  }

  @Test
  void uploadFile_ThrowsFileAlreadyExists_WhenContentConflict() throws Exception {
    // Arrange
    String userId = testUserId;
    String uniqueRequestFilename = "uniqueForContentConflictTest.txt";
    FileUploadRequest uploadRequest =
        new FileUploadRequest(uniqueRequestFilename, Visibility.PRIVATE, List.of("tC"));
    String contentThatWillConflict = "this content will cause a SHA256 conflict";
    String expectedSha256 = "sha256ForConflictContent"; // This will be simulated by the builder

    // REMOVE local when(mockMultipartFile.getInputStream()) - rely on setUp()
    // Other MultipartFile mocks (isEmpty, getOriginalFilename, getContentType, getSize) should rely
    // on lenient setUp().

    try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
      MimeUtil.Detected detected =
          new MimeUtil.Detected(
              new LookaheadInputStream(
                  new ByteArrayInputStream(contentThatWillConflict.getBytes()), 64 * 1024),
              "application/octet-stream" // Default or specific for test
              );
      mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);

      FileRecord recordBuiltForConflict =
          FileRecord.builder()
              .id(UUID.randomUUID().toString())
              .filename(UUID.randomUUID().toString())
              .originalFilename(uniqueRequestFilename)
              .ownerId(userId)
              .visibility(uploadRequest.visibility())
              .tags(uploadRequest.tags())
              .contentType("application/octet-stream")
              .size((long) contentThatWillConflict.getBytes().length)
              .uploadDate(new Date())
              .token(UUID.randomUUID().toString())
              .sha256(expectedSha256)
              .build();
      when(fileMetadataBuilder.build(eq(uploadRequest), eq(userId), eq(mockMultipartFile)))
          .thenReturn(recordBuiltForConflict);

      String mongoErrorMessageDetail =
          "E11000 duplicate key error collection: test.fs.files index: owner_sha256_idx dup key: { metadata.sha256: \""
              + expectedSha256
              + "\" }";
      when(gridFsHelper.storeAndHash(eq(mockMultipartFile), eq(recordBuiltForConflict)))
          .thenThrow(
              new MongoWriteException(
                  new WriteError(
                      11000,
                      "owner_sha256_idx conflict",
                      new BsonDocument("details", new BsonString(mongoErrorMessageDetail))),
                  new ServerAddress(),
                  Collections.emptySet()));

      // Act & Assert
      FileAlreadyExistsException ex =
          assertThrows(
              FileAlreadyExistsException.class,
              () -> {
                fileService.uploadFile(userId, mockMultipartFile, uploadRequest);
              });

      assertTrue(
          ex.getMessage()
              .contains(
                  "Content with hash '" + expectedSha256 + "' already exists for this user."));

      verify(fileMetadataBuilder).build(eq(uploadRequest), eq(userId), eq(mockMultipartFile));
      verify(gridFsHelper).storeAndHash(eq(mockMultipartFile), eq(recordBuiltForConflict));
      verify(fileRecordRepository, never()).save(any(FileRecord.class));
    }
  }

  @Test
  void uploadFile_ThrowsInvalidRequestArgument_WhenFileIsEmpty() {
    when(mockMultipartFile.isEmpty()).thenReturn(true);
    InvalidRequestArgumentException ex =
        assertThrows(
            InvalidRequestArgumentException.class,
            () -> {
              fileService.uploadFile(testUserId, mockMultipartFile, defaultUploadRequest);
            });
    assertEquals(
        "File is empty", ex.getMessage()); // Adjusted message if necessary based on actual behavior
    verify(fileRecordRepository, never()).save(any());
  }

  @Test
  void uploadFile_genericRuntimeException_fromGridFsHelper_propagates() throws Exception {
    // Arrange
    FileUploadRequest request =
        new FileUploadRequest("generic_error.txt", Visibility.PRIVATE, List.of());
    FileRecord builtRecord =
        FileRecord.builder()
            .id("anyId")
            .filename("anyId")
            .originalFilename("generic_error.txt")
            .ownerId(testUserId)
            .sha256("genericSha")
            .build();
    when(fileMetadataBuilder.build(eq(request), eq(testUserId), eq(mockMultipartFile)))
        .thenReturn(builtRecord);

    RuntimeException rootCause = new RuntimeException("Simulated internal gridfs error");
    when(gridFsHelper.storeAndHash(eq(mockMultipartFile), eq(builtRecord))).thenThrow(rootCause);

    // Act & Assert
    // Verify that a raw RuntimeException from a dependency propagates if not specifically handled
    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> {
              fileService.uploadFile(testUserId, mockMultipartFile, request);
            });
    assertEquals(rootCause, ex, "The original RuntimeException should be propagated.");
  }
}
