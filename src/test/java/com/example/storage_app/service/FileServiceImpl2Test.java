package com.example.storage_app.service;

// Corrected and Organized Imports following Spotless preferences

// Static JUnit & Mockito
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
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
import java.util.ArrayList;
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
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.web.multipart.MultipartFile;

// import com.mongodb.client.gridfs.model.GridFSFile; // Likely unused now
// import org.bs.Document; // Likely unused directly
// import org.bs.types.ObjectId; // Likely unused directly

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileServiceImpl2Test {

  @Mock private GridFsTemplate gridFsTemplate;
  @Mock private FileRecordRepository fileRecordRepository;
  @Mock private FileMetadataBuilder fileMetadataBuilder;
  @Mock private FileMapper fileMapper;
  @Mock private GridFsHelper gridFsHelper;

  @Mock private MultipartFile mockFile;

  @InjectMocks private FileServiceImpl fileService;

  private String testUserId;
  private String testFileId;
  private FileUpdateRequest updateRequest;
  private FileUploadRequest defaultUploadRequest;

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
    testFileId = UUID.randomUUID().toString();
    updateRequest = new FileUpdateRequest("new_user_filename.txt");
    defaultUploadRequest =
        new FileUploadRequest(
            "user_requested_file.txt", Visibility.PRIVATE, List.of("tag1", "tag2"));

    lenient().when(mockFile.getOriginalFilename()).thenReturn("original_multipart_filename.txt");
    lenient().when(mockFile.isEmpty()).thenReturn(false);
    lenient().when(mockFile.getSize()).thenReturn(12L);
    lenient().when(mockFile.getContentType()).thenReturn("text/plain");

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
              if (fur.filename() != null) {
                determinedOriginalFilename = fur.filename();
              } else {
                determinedOriginalFilename = mf.getOriginalFilename();
              }
              if (determinedOriginalFilename == null) {
                determinedOriginalFilename = "default_original.txt";
              }
              return FileRecord.builder()
                  .id(UUID.randomUUID().toString())
                  .filename(UUID.randomUUID().toString())
                  .originalFilename(determinedOriginalFilename)
                  .ownerId(uid)
                  .visibility(fur.visibility())
                  .tags(
                      fur.tags() != null
                          ? fur.tags().stream()
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
                  .sha256("mocksha256value-from-builder")
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
                  return new FileStorageResult(
                      new org.bson.types.ObjectId(),
                      record.getSha256(),
                      cType,
                      size,
                      new org.bson.Document("originalFilename", oFilename));
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
              if (recordToSave.getId() == null) {
                recordToSave.setId(UUID.randomUUID().toString());
              }
              if (recordToSave.getFilename() == null) {
                recordToSave.setFilename(UUID.randomUUID().toString());
              }
              return recordToSave;
            });
    lenient().when(fileRecordRepository.findById(anyString())).thenReturn(Optional.empty());
    lenient().when(fileRecordRepository.findByToken(anyString())).thenReturn(Optional.empty());
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
        .when(
            fileRecordRepository.findByOwnerIdAndTagsContaining(
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

  // Tests for uploadFile start here
  private void mockMimeDetect(
      MockedStatic<MimeUtil> mockedMimeUtil, String contentType, InputStream inputStream)
      throws IOException {
    LookaheadInputStream lookaheadInputStream = new LookaheadInputStream(inputStream, 64 * 1024);
    MimeUtil.Detected detected = new MimeUtil.Detected(lookaheadInputStream, contentType);
    mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
  }

  @Test
  void uploadFile_Success_WithGivenFilename() throws Exception {
    try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
      MimeUtil.Detected detected =
          new MimeUtil.Detected(
              new LookaheadInputStream(
                  new ByteArrayInputStream("test content".getBytes()), 64 * 1024),
              "text/plain");
      mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);

      FileResponse response = fileService.uploadFile(testUserId, mockFile, defaultUploadRequest);

      assertNotNull(response);
      assertEquals(defaultUploadRequest.filename(), response.filename());

      ArgumentCaptor<FileRecord> recordForStoreCaptor = ArgumentCaptor.forClass(FileRecord.class);
      verify(gridFsHelper).storeAndHash(eq(mockFile), recordForStoreCaptor.capture());
      FileRecord capturedRecordForStore = recordForStoreCaptor.getValue();
      assertEquals(defaultUploadRequest.filename(), capturedRecordForStore.getOriginalFilename());
      assertEquals(testUserId, capturedRecordForStore.getOwnerId());
      assertEquals("mocksha256value-from-builder", capturedRecordForStore.getSha256());
      verify(fileMapper).fromEntity(capturedRecordForStore);
    }
  }

  @Test
  void uploadFile_Success_UsesOriginalFilename_WhenRequestFilenameNull() throws Exception {
    try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
      MimeUtil.Detected detected =
          new MimeUtil.Detected(
              new LookaheadInputStream(
                  new ByteArrayInputStream("test content".getBytes()), 64 * 1024),
              "image/jpeg");
      mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
      when(mockFile.getInputStream())
          .thenReturn(new ByteArrayInputStream("test content".getBytes()))
          .thenReturn(new ByteArrayInputStream("test content".getBytes()));

      String multipartOriginalFilename = "from_multipart.jpg";
      when(mockFile.getOriginalFilename()).thenReturn(multipartOriginalFilename);
      when(mockFile.getContentType()).thenReturn("image/jpeg");

      FileUploadRequest requestWithNullFilename =
          new FileUploadRequest(null, Visibility.PUBLIC, List.of("photo"));

      when(fileRecordRepository.existsByOwnerIdAndOriginalFilename(eq(testUserId), isNull()))
          .thenReturn(false);
      when(fileRecordRepository.existsByOwnerIdAndOriginalFilename(
              eq(testUserId), eq(multipartOriginalFilename)))
          .thenReturn(false);

      FileResponse response = fileService.uploadFile(testUserId, mockFile, requestWithNullFilename);

      assertNotNull(response);
      assertEquals(multipartOriginalFilename, response.filename());

      ArgumentCaptor<FileRecord> recordForMapCaptor = ArgumentCaptor.forClass(FileRecord.class);
      verify(fileMapper).fromEntity(recordForMapCaptor.capture());
      assertEquals(multipartOriginalFilename, recordForMapCaptor.getValue().getOriginalFilename());
    }
  }

  @Test
  void uploadFile_Success_UsesOriginalFilename_WhenRequestFilenameBlank() throws Exception {
    try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
      MimeUtil.Detected detected =
          new MimeUtil.Detected(
              new LookaheadInputStream(
                  new ByteArrayInputStream("test content".getBytes()), 64 * 1024),
              "image/png");
      mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
      when(mockFile.getInputStream())
          .thenReturn(new ByteArrayInputStream("test content".getBytes()))
          .thenReturn(new ByteArrayInputStream("test content".getBytes()));

      String multipartOriginalFilename = "from_multipart.png";
      String blankRequestFilename = "   ";
      when(mockFile.getOriginalFilename()).thenReturn(multipartOriginalFilename);
      when(mockFile.getContentType()).thenReturn("image/png");

      FileUploadRequest requestWithBlankFilename =
          new FileUploadRequest(blankRequestFilename, Visibility.PUBLIC, List.of("image"));

      when(fileRecordRepository.existsByOwnerIdAndOriginalFilename(
              eq(testUserId), eq(blankRequestFilename)))
          .thenReturn(false);

      FileResponse response =
          fileService.uploadFile(testUserId, mockFile, requestWithBlankFilename);

      assertNotNull(response);
      assertEquals(blankRequestFilename, response.filename());

      ArgumentCaptor<FileRecord> recordForMapCaptor = ArgumentCaptor.forClass(FileRecord.class);
      verify(fileMapper).fromEntity(recordForMapCaptor.capture());
      assertEquals(blankRequestFilename, recordForMapCaptor.getValue().getOriginalFilename());
    }
  }

  @Test
  void uploadFile_ThrowsInvalidRequestArgument_WhenTagsExceedMax() {
    List<String> tooManyTags = List.of("1", "2", "3", "4", "5", "6");
    FileUploadRequest requestWithTooManyTags =
        new FileUploadRequest("file.txt", Visibility.PUBLIC, tooManyTags);

    // Specific mock for this test:
    when(fileMetadataBuilder.build(eq(requestWithTooManyTags), eq(testUserId), eq(mockFile)))
        .thenThrow(new InvalidRequestArgumentException("A maximum of 5 tags are allowed."));

    InvalidRequestArgumentException ex =
        assertThrows(
            InvalidRequestArgumentException.class,
            () -> {
              fileService.uploadFile(testUserId, mockFile, requestWithTooManyTags);
            });
    assertTrue(ex.getMessage().contains("A maximum of 5 tags are allowed"));
  }

  @Test
  void uploadFile_ThrowsInvalidRequestArgument_WhenFileIsEmpty() {
    when(mockFile.isEmpty()).thenReturn(true);
    InvalidRequestArgumentException ex =
        assertThrows(
            InvalidRequestArgumentException.class,
            () -> {
              fileService.uploadFile(testUserId, mockFile, defaultUploadRequest);
            });
    assertEquals("File is empty", ex.getMessage());
  }

  @Test
  void uploadFile_HandlesNullOriginalTagsInRequest() throws Exception {
    try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
      MimeUtil.Detected detected =
          new MimeUtil.Detected(
              new LookaheadInputStream(
                  new ByteArrayInputStream("test content".getBytes()), 64 * 1024),
              "text/plain");
      mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);

      FileUploadRequest requestWithNullTags =
          new FileUploadRequest("file.txt", Visibility.PUBLIC, null);

      FileResponse response = fileService.uploadFile(testUserId, mockFile, requestWithNullTags);

      assertNotNull(response);
      assertTrue(response.tags().isEmpty(), "Tags should be empty when null list is provided");
    }
  }

  @Test
  void uploadFile_HandlesNullTagWithinOriginalTagsList() throws Exception {
    try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
      MimeUtil.Detected detected =
          new MimeUtil.Detected(
              new LookaheadInputStream(
                  new ByteArrayInputStream("test content".getBytes()), 64 * 1024),
              "text/plain");
      mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);

      List<String> tagsWithNull = new ArrayList<>();
      tagsWithNull.add("tag1");
      tagsWithNull.add(null);
      tagsWithNull.add("tag2");
      FileUploadRequest requestWithNullInTags =
          new FileUploadRequest("file_with_null_tag.txt", Visibility.PUBLIC, tagsWithNull);

      FileResponse response = fileService.uploadFile(testUserId, mockFile, requestWithNullInTags);

      assertNotNull(response);
      assertEquals(2, response.tags().size(), "Should contain 2 non-null tags after filtering");
      assertTrue(
          response.tags().containsAll(List.of("tag1", "tag2")), "Tags should be 'tag1' and 'tag2'");
    }
  }

  @Test
  void uploadFile_ThrowsFileAlreadyExists_WhenFilenameConflict() throws Exception {
    // Simulate MongoWriteException due to filename conflict from GridFsHelper
    // FileRecord recordBuiltForFilenameConflict = // This line is no longer strictly needed for the
    // mock trigger
    //     fileMetadataBuilder.build(defaultUploadRequest, testUserId, mockFile);
    String expectedMongoErrorMessagePart =
        "E11000 duplicate key error collection: test.fs.files index: owner_filename_idx dup key: { metadata.ownerId: \""
            + testUserId
            + "\", metadata.originalFilename: \""
            + defaultUploadRequest.filename()
            + "\" }";
    when(gridFsHelper.storeAndHash(
            eq(mockFile),
            argThat(
                record ->
                    defaultUploadRequest
                            .filename()
                            .equals(record.getOriginalFilename()) // Match by requested filename
                        && testUserId.equals(record.getOwnerId()) // Match by ownerId
                // && record.getId().equals(recordBuiltForFilenameConflict.getId()) // Removed ID
                // check
                )))
        .thenThrow(
            new MongoWriteException(
                new WriteError(
                    11000,
                    "owner_filename_idx conflict",
                    new BsonDocument("details", new BsonString(expectedMongoErrorMessagePart))),
                new ServerAddress(),
                Collections.emptySet()));
    FileAlreadyExistsException ex =
        assertThrows(
            FileAlreadyExistsException.class,
            () -> fileService.uploadFile(testUserId, mockFile, defaultUploadRequest));
    String expectedMessage =
        "Filename '" + defaultUploadRequest.filename() + "' already exists for this user.";
    assertEquals(expectedMessage, ex.getMessage());
    verify(gridFsHelper).storeAndHash(eq(mockFile), any(FileRecord.class));
    verify(fileRecordRepository, never()).save(any(FileRecord.class));
  }

  @Test
  void uploadFile_ThrowsFileAlreadyExists_WhenContentConflict() throws Exception {
    try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
      String testContent = "test content with specific hash";
      String expectedMimeType = "text/plain";
      InputStream contentStreamForDetect = new ByteArrayInputStream(testContent.getBytes());
      MimeUtil.Detected detected =
          new MimeUtil.Detected(
              new LookaheadInputStream(contentStreamForDetect, 64 * 1024), expectedMimeType);
      mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
      when(mockFile.getInputStream())
          .thenReturn(new ByteArrayInputStream(testContent.getBytes()))
          .thenReturn(new ByteArrayInputStream(testContent.getBytes()));
      when(mockFile.getSize()).thenReturn((long) testContent.getBytes().length);
      when(mockFile.getContentType()).thenReturn(expectedMimeType);
      when(fileRecordRepository.existsByOwnerIdAndOriginalFilename(
              eq(testUserId), eq(defaultUploadRequest.filename())))
          .thenReturn(false);
      FileRecord recordAsBuilt =
          fileMetadataBuilder.build(defaultUploadRequest, testUserId, mockFile);
      String expectedSha256 = recordAsBuilt.getSha256();
      when(fileRecordRepository.existsByOwnerIdAndOriginalFilename(
              eq(testUserId), eq(defaultUploadRequest.filename())))
          .thenReturn(false);
      String expectedMongoErrorMessagePartForSha =
          "E11000 duplicate key error collection: test.fs.files index: owner_sha256_idx dup key: { metadata.sha256: \""
              + expectedSha256
              + "\" }";
      when(gridFsHelper.storeAndHash(
              eq(mockFile),
              argThat(
                  record ->
                      expectedSha256.equals(record.getSha256())
                          && defaultUploadRequest.filename().equals(record.getOriginalFilename()))))
          .thenThrow(
              new MongoWriteException(
                  new WriteError(
                      11000,
                      "owner_sha256_idx conflict",
                      new BsonDocument(
                          "details", new BsonString(expectedMongoErrorMessagePartForSha))),
                  new ServerAddress(),
                  Collections.emptySet()));
      FileAlreadyExistsException ex =
          assertThrows(
              FileAlreadyExistsException.class,
              () -> fileService.uploadFile(testUserId, mockFile, defaultUploadRequest));
      assertTrue(
          ex.getMessage()
              .contains(
                  "Content with hash '" + expectedSha256 + "' already exists for this user."));
      verify(gridFsHelper)
          .storeAndHash(eq(mockFile), argThat(r -> expectedSha256.equals(r.getSha256())));
      verify(fileRecordRepository, never()).save(any(FileRecord.class));
    }
  }

  @Test
  void
      uploadFile_OriginalFilenameIsNullAndRequestFilenameIsNull_LeadsToInvalidRequestArgumentException()
          throws Exception {
    when(mockFile.getOriginalFilename()).thenReturn(null);
    FileUploadRequest requestWithNullFilename =
        new FileUploadRequest(null, Visibility.PRIVATE, Collections.emptyList());

    lenient()
        .when(fileMetadataBuilder.build(eq(requestWithNullFilename), eq(testUserId), eq(mockFile)))
        .thenThrow(new InvalidRequestArgumentException("Filename cannot be empty."));

    InvalidRequestArgumentException ex =
        assertThrows(
            InvalidRequestArgumentException.class,
            () -> fileService.uploadFile(testUserId, mockFile, requestWithNullFilename));

    assertEquals("Filename cannot be empty.", ex.getMessage());

    verify(fileMetadataBuilder).build(eq(requestWithNullFilename), eq(testUserId), eq(mockFile));
    verify(gridFsHelper, never()).storeAndHash(any(), any());
    verify(fileRecordRepository, never()).save(any(FileRecord.class));
  }

  // End of uploadFile tests

  @Test
  void updateFileDetails_Success() {
    FileRecord existingRecord = new FileRecord();
    existingRecord.setId(testFileId);
    existingRecord.setFilename(testFileId); // Assuming fileId is the system filename
    existingRecord.setOwnerId(testUserId);
    existingRecord.setOriginalFilename("old_user_filename.txt");
    existingRecord.setToken("existing-token");

    when(fileRecordRepository.findByFilename(testFileId))
        .thenReturn(Optional.of(existingRecord)); // Changed findById to findByFilename
    when(fileRecordRepository.save(any(FileRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    when(fileMapper.fromEntity(any(FileRecord.class)))
        .thenAnswer(
            inv -> {
              FileRecord fr = inv.getArgument(0);
              return new FileResponse(
                  fr.getId(),
                  fr.getOriginalFilename(), // This should be the new filename
                  fr.getVisibility(),
                  fr.getTags(),
                  fr.getUploadDate(),
                  fr.getContentType(),
                  fr.getSize(),
                  "/api/v1/files/download/" + fr.getToken());
            });

    FileResponse response = fileService.updateFileDetails(testUserId, testFileId, updateRequest);

    assertNotNull(response);
    assertEquals(updateRequest.newFilename(), response.filename());
    verify(fileRecordRepository).findByFilename(testFileId); // Verify findByFilename
    verify(fileRecordRepository)
        .save(argThat(record -> updateRequest.newFilename().equals(record.getOriginalFilename())));
    verify(fileMapper).fromEntity(any(FileRecord.class));
  }

  @Test
  void updateFileDetails_NewFilenameConflictsWithExisting() {
    FileRecord initialRecord =
        FileRecord.builder().id(testFileId).originalFilename("old.txt").ownerId(testUserId).build();
    when(fileRecordRepository.findByFilename(eq(testFileId)))
        .thenReturn(Optional.of(initialRecord));
    when(fileRecordRepository.save(any(FileRecord.class)))
        .thenThrow(
            new org.springframework.dao.DuplicateKeyException(
                "E11000 duplicate key error collection: fs.files index: owner_filename_idx dup key: { ... }"));

    assertThrows(
        FileAlreadyExistsException.class,
        () -> fileService.updateFileDetails(testUserId, testFileId, updateRequest));

    verify(fileRecordRepository).findByFilename(eq(testFileId));
    verify(fileRecordRepository).save(any(FileRecord.class));
  }

  @Test
  void updateFileDetails_UpdateOperationFails_ShouldThrowStorageException() {
    FileRecord initialRecord =
        FileRecord.builder().id(testFileId).originalFilename("old.txt").ownerId(testUserId).build();
    when(fileRecordRepository.findByFilename(eq(testFileId)))
        .thenReturn(Optional.of(initialRecord));
    when(fileRecordRepository.save(any(FileRecord.class)))
        .thenThrow(new StorageException("Simulated save failure"));

    assertThrows(
        StorageException.class,
        () -> fileService.updateFileDetails(testUserId, testFileId, updateRequest));

    verify(fileRecordRepository).findByFilename(eq(testFileId));
    verify(fileRecordRepository).save(any(FileRecord.class));
  }

  @Test
  void deleteFile_Success() {
    FileRecord record = new FileRecord();
    record.setId(testFileId);
    record.setFilename(testFileId); // Set filename to match what service uses for query
    record.setOwnerId(testUserId);
    record.setOriginalFilename("test_original_delete.txt");
    record.setToken("delete-token");

    when(fileRecordRepository.findByFilename(testFileId))
        .thenReturn(Optional.of(record)); // Changed findById to findByFilename

    GridFSFile mockGridFSFile = mock(GridFSFile.class);
    // Mock ObjectId for GridFSFile as it's used in logging and potentially by delete query logic
    ObjectId mockObjectId = new ObjectId();
    when(mockGridFSFile.getObjectId()).thenReturn(mockObjectId);
    when(mockGridFSFile.getFilename()).thenReturn(testFileId); // Ensure filename matches

    when(gridFsTemplate.findOne(any(Query.class))).thenReturn(mockGridFSFile);
    doNothing().when(gridFsTemplate).delete(any(Query.class));
    doNothing().when(fileRecordRepository).delete(record);

    fileService.deleteFile(testUserId, testFileId);

    verify(fileRecordRepository).findByFilename(testFileId); // Verify findByFilename
    // Verify that delete on GridFsTemplate uses the ObjectId from the found GridFSFile
    verify(gridFsTemplate)
        .delete(
            argThat(
                query ->
                    query.getQueryObject().containsKey("_id")
                        && query.getQueryObject().get("_id").equals(mockObjectId)));
    verify(fileRecordRepository).delete(record);
  }

  @Test
  void deleteFile_NotFound_ShouldThrowResourceNotFound() {
    String NON_EXISTENT_ID = "non-existent-file-id";
    when(fileRecordRepository.findByFilename(NON_EXISTENT_ID))
        .thenReturn(Optional.empty()); // Changed findById to findByFilename

    assertThrows(
        ResourceNotFoundException.class,
        () -> {
          fileService.deleteFile(testUserId, NON_EXISTENT_ID);
        });

    verify(fileRecordRepository).findByFilename(NON_EXISTENT_ID); // Verify findByFilename
  }

  @Test
  void deleteFile_UnauthorizedUser() {
    String systemFileId = testFileId;
    String actualOwnerId = "anotherUser123";
    FileRecord recordOwnedByOther =
        FileRecord.builder()
            .id(systemFileId)
            .filename("some-gridfs-filename.dat")
            .ownerId(actualOwnerId)
            .originalFilename("secret.dat")
            .build();

    when(fileRecordRepository.findByFilename(eq(systemFileId)))
        .thenReturn(Optional.of(recordOwnedByOther));

    UnauthorizedOperationException ex =
        assertThrows(
            UnauthorizedOperationException.class,
            () -> {
              fileService.deleteFile(testUserId, systemFileId);
            });

    assertTrue(
        ex.getMessage()
            .contains(
                "User '" + testUserId + "' not authorized to delete fileId: " + systemFileId));

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
            .filename("data.bin")
            .ownerId(null)
            .originalFilename("data.bin")
            .build();

    when(fileRecordRepository.findByFilename(eq(systemFileId)))
        .thenReturn(Optional.of(recordWithNullOwner));

    assertThrows(
        UnauthorizedOperationException.class,
        () -> {
          fileService.deleteFile(testUserId, systemFileId);
        });

    verify(fileRecordRepository).findByFilename(eq(systemFileId));
    verify(gridFsTemplate, never()).delete(any(Query.class));
    verify(fileRecordRepository, never()).delete(any(FileRecord.class));
  }

  @Test
  void listFiles_ByUser_Defaults() {
    String userId = testUserId;
    int pageNum = 0;
    int pageSize = 10;
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, "uploadDate"));

    String systemUUID1 = UUID.randomUUID().toString();
    String userOriginalFilename1 = "user_file_alpha.txt";
    FileRecord record1 =
        FileRecord.builder()
            .id(systemUUID1)
            .filename(systemUUID1)
            .originalFilename(userOriginalFilename1)
            .ownerId(userId)
            .visibility(Visibility.PRIVATE)
            .uploadDate(new Date(System.currentTimeMillis() - 20000))
            .contentType("text/plain")
            .tags(List.of("user"))
            .token("token_alpha")
            .size(150L)
            .build();

    FileResponse response1 =
        new FileResponse(
            systemUUID1,
            userOriginalFilename1,
            Visibility.PRIVATE,
            record1.getTags(),
            record1.getUploadDate(),
            record1.getContentType(),
            record1.getSize(),
            "/dl/" + record1.getToken());

    List<FileRecord> userRecords = List.of(record1);
    Page<FileRecord> recordPage = new PageImpl<>(userRecords, expectedPageable, userRecords.size());

    when(fileRecordRepository.findByOwnerId(eq(userId), eq(expectedPageable)))
        .thenReturn(recordPage);
    when(fileMapper.fromEntity(eq(record1))).thenReturn(response1);

    Page<FileResponse> resultPage =
        fileService.listFiles(userId, null, null, "asc", pageNum, pageSize);

    assertNotNull(resultPage);
    assertEquals(1, resultPage.getTotalElements());
    assertEquals(1, resultPage.getContent().size());
    FileResponse actualResponseFile = resultPage.getContent().get(0);
    assertEquals(response1, actualResponseFile);
    assertEquals(userOriginalFilename1, actualResponseFile.filename());

    verify(fileRecordRepository).findByOwnerId(eq(userId), eq(expectedPageable));
    verify(fileMapper).fromEntity(eq(record1));
  }

  @Test
  void listFiles_Public_Defaults() {
    int pageNum = 0;
    int pageSize = 10;
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, "uploadDate"));

    String systemUUID = UUID.randomUUID().toString();
    String originalFilename = "public_file.txt";
    FileRecord publicRecord =
        FileRecord.builder()
            .id(systemUUID)
            .filename(systemUUID)
            .originalFilename(originalFilename)
            .visibility(Visibility.PUBLIC)
            .ownerId("anotherUser")
            .uploadDate(new Date())
            .contentType("image/png")
            .tags(List.of("public_tag"))
            .token("tokenPublic1")
            .size(200L)
            .build();
    FileResponse publicResponse =
        new FileResponse(
            systemUUID,
            originalFilename,
            Visibility.PUBLIC,
            List.of("public_tag"),
            publicRecord.getUploadDate(),
            "image/png",
            200L,
            "/api/v1/files/download/tokenPublic1");

    List<FileRecord> records = List.of(publicRecord);
    Page<FileRecord> recordPage = new PageImpl<>(records, expectedPageable, records.size());

    when(fileRecordRepository.findByVisibility(eq("PUBLIC"), eq(expectedPageable)))
        .thenReturn(recordPage);

    when(fileMapper.fromEntity(eq(publicRecord))).thenReturn(publicResponse);

    Page<FileResponse> resultPage =
        fileService.listFiles(null, null, null, "asc", pageNum, pageSize);

    assertNotNull(resultPage);
    assertEquals(1, resultPage.getTotalElements());
    assertEquals(1, resultPage.getContent().size());
    assertEquals(publicResponse, resultPage.getContent().get(0));

    verify(fileRecordRepository).findByVisibility(eq("PUBLIC"), eq(expectedPageable));
    verify(fileMapper).fromEntity(eq(publicRecord));
    verify(fileRecordRepository, never()).findByOwnerId(anyString(), any(Pageable.class));
    verify(fileRecordRepository, never())
        .findByVisibilityAndTagsContaining(anyString(), anyString(), any(Pageable.class));
  }

  @Test
  void listFiles_ByUser_WithTag_SortDesc_Paginated() {
    String userId = testUserId;
    String filterTagApi = "ProjectX";
    String sortByApi = "filename";
    String sortOrderApi = "DeSc";
    int pageNum = 0;
    int pageSize = 5;
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "originalFilename"));

    String systemUUID = UUID.randomUUID().toString();
    String userOriginalFilename = "report.pdf";
    FileRecord record1 =
        FileRecord.builder()
            .id(systemUUID)
            .filename(systemUUID)
            .originalFilename(userOriginalFilename)
            .ownerId(userId)
            .visibility(Visibility.PRIVATE)
            .tags(List.of(filterTagApi.toLowerCase(), "annual"))
            .uploadDate(new Date())
            .contentType("application/pdf")
            .token("userTokenForTagTest")
            .size(5000L)
            .build();
    FileResponse response1 =
        new FileResponse(
            systemUUID,
            userOriginalFilename,
            Visibility.PRIVATE,
            record1.getTags(),
            record1.getUploadDate(),
            "application/pdf",
            5000L,
            "/dl/userTokenForTagTest");

    List<FileRecord> userRecords = List.of(record1);
    Page<FileRecord> recordPage = new PageImpl<>(userRecords, expectedPageable, userRecords.size());

    when(fileRecordRepository.findByOwnerIdAndTagsContaining(
            eq(userId), eq(filterTagApi.toLowerCase()), eq(expectedPageable)))
        .thenReturn(recordPage);
    when(fileRecordRepository.findByOwnerId(eq(userId), eq(expectedPageable)))
        .thenReturn(Page.empty());

    when(fileMapper.fromEntity(eq(record1))).thenReturn(response1);

    Page<FileResponse> resultPage =
        fileService.listFiles(userId, filterTagApi, sortByApi, sortOrderApi, pageNum, pageSize);

    assertNotNull(resultPage);
    assertEquals(1, resultPage.getTotalElements());
    assertEquals(1, resultPage.getContent().size());
    assertEquals(response1, resultPage.getContent().get(0));

    verify(fileRecordRepository)
        .findByOwnerIdAndTagsContaining(
            eq(userId), eq(filterTagApi.toLowerCase()), eq(expectedPageable));
    verify(fileMapper).fromEntity(eq(record1));
    verify(fileRecordRepository, never())
        .findByVisibilityAndTagsContaining(anyString(), anyString(), any(Pageable.class));
  }

  @Test
  void listFiles_EmptyResult() {
    String userIdToQuery = "userWhoMightOrMightNotExistOrHasNoFiles";
    int pageNum = 0;
    int pageSize = 10;
    String sortByApi = "uploadDate";
    String sortDir = "desc";
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "uploadDate"));

    when(fileRecordRepository.findByOwnerId(eq(userIdToQuery), eq(expectedPageable)))
        .thenReturn(new PageImpl<>(Collections.emptyList(), expectedPageable, 0));

    Page<FileResponse> resultPageUser =
        fileService.listFiles(userIdToQuery, null, sortByApi, sortDir, pageNum, pageSize);
    assertNotNull(resultPageUser);
    assertTrue(resultPageUser.isEmpty());
    assertEquals(0, resultPageUser.getTotalElements());
    verify(fileRecordRepository).findByOwnerId(eq(userIdToQuery), eq(expectedPageable));
    verify(fileMapper, never()).fromEntity(any(FileRecord.class));

    String nonExistentTag = "no_such_tag_exists";
    Pageable publicPageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "uploadDate"));
    when(fileRecordRepository.findByVisibilityAndTagsContaining(
            eq("PUBLIC"), eq(nonExistentTag), eq(publicPageable)))
        .thenReturn(new PageImpl<>(Collections.emptyList(), publicPageable, 0));

    Page<FileResponse> resultPagePublicTag =
        fileService.listFiles(null, nonExistentTag, null, "asc", 0, 10);
    assertNotNull(resultPagePublicTag);
    assertTrue(resultPagePublicTag.isEmpty());
    verify(fileRecordRepository)
        .findByVisibilityAndTagsContaining(eq("PUBLIC"), eq(nonExistentTag), eq(publicPageable));
  }

  @Test
  void listFiles_DataMapping_Variations_ForPublicListing() {
    int pageNum = 0;
    int pageSize = 10;
    Pageable expectedPageable =
        PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, "uploadDate"));

    String id1 = UUID.randomUUID().toString();
    FileRecord recPublicSizeZero =
        FileRecord.builder()
            .id(id1)
            .filename(id1)
            .originalFilename("zero.txt")
            .ownerId("uA")
            .visibility(Visibility.PUBLIC)
            .token("tA")
            .uploadDate(new Date())
            .contentType("text/plain")
            .size(0L)
            .tags(Collections.emptyList())
            .build();
    FileResponse respPublicSizeZero =
        new FileResponse(
            id1,
            "zero.txt",
            Visibility.PUBLIC,
            Collections.emptyList(),
            recPublicSizeZero.getUploadDate(),
            "text/plain",
            0L,
            "/dl/" + id1);

    String id2 = UUID.randomUUID().toString();
    FileRecord recPublicNullTags =
        FileRecord.builder()
            .id(id2)
            .filename(id2)
            .originalFilename("null_tags.dat")
            .ownerId("uB")
            .visibility(Visibility.PUBLIC)
            .token("tB")
            .uploadDate(new Date())
            .contentType("app/dat")
            .size(100L)
            .tags(null)
            .build();
    FileResponse respPublicNullTags =
        new FileResponse(
            id2,
            "null_tags.dat",
            Visibility.PUBLIC,
            Collections.emptyList(),
            recPublicNullTags.getUploadDate(),
            "app/dat",
            100L,
            "/dl/" + id2);

    String id3 = UUID.randomUUID().toString();
    FileRecord recPrivate =
        FileRecord.builder()
            .id(id3)
            .filename(id3)
            .originalFilename("private.doc")
            .ownerId("uC")
            .visibility(Visibility.PRIVATE)
            .token("tC")
            .uploadDate(new Date())
            .contentType("app/doc")
            .size(200L)
            .tags(List.of("secret"))
            .build();

    List<FileRecord> publicRecordsFromRepo = List.of(recPublicSizeZero, recPublicNullTags);
    Page<FileRecord> recordPage =
        new PageImpl<>(publicRecordsFromRepo, expectedPageable, publicRecordsFromRepo.size());

    when(fileRecordRepository.findByVisibility(eq("PUBLIC"), eq(expectedPageable)))
        .thenReturn(recordPage);
    when(fileMapper.fromEntity(eq(recPublicSizeZero))).thenReturn(respPublicSizeZero);
    when(fileMapper.fromEntity(eq(recPublicNullTags))).thenReturn(respPublicNullTags);

    Page<FileResponse> resultsPage =
        fileService.listFiles(null, null, null, "asc", pageNum, pageSize);
    List<FileResponse> actualResponses = resultsPage.getContent();

    assertEquals(2, resultsPage.getTotalElements(), "Should only retrieve 2 public files");
    assertEquals(2, actualResponses.size(), "Should map 2 public files");
    assertTrue(actualResponses.contains(respPublicSizeZero));
    assertTrue(actualResponses.contains(respPublicNullTags));

    verify(fileRecordRepository).findByVisibility(eq("PUBLIC"), eq(expectedPageable));
    verify(fileMapper).fromEntity(eq(recPublicSizeZero));
    verify(fileMapper).fromEntity(eq(recPublicNullTags));
    verify(fileMapper, never()).fromEntity(eq(recPrivate));
  }
}
