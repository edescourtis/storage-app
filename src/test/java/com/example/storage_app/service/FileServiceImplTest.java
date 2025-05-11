package com.example.storage_app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.example.storage_app.model.Visibility;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.result.UpdateResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {
  @Mock private GridFsTemplate gridFsTemplate;

  @Mock private MongoTemplate mongoTemplate;

  @InjectMocks private FileServiceImpl fileService;

  @Mock private MultipartFile mockMultipartFile;

  private FileUploadRequest fileUploadRequest;
  private String testUserId = "user123";
  private String testFilename = "test.txt";
  private String testContent = "Hello World";
  private ObjectId testObjectId = new ObjectId();
  private String testToken = "test-download-token";
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
  void setUp() {
    fileUploadRequest = new FileUploadRequest(testFilename, Visibility.PRIVATE, List.of("tag1"));
    testFileId = UUID.randomUUID().toString();
  }

  @Test
  void uploadFile_whenFilenameExistsForUser_shouldThrowIllegalArgumentException()
      throws IOException, NoSuchAlgorithmException {
    // Only mockMultipartFile.isEmpty() and the mongoTemplate.exists() for filename conflict are
    // needed.
    // when(mockMultipartFile.isEmpty()).thenReturn(false); // This was determined to be unnecessary
    // for this path

    Query expectedFilenameConflictQuery =
        Query.query(
            Criteria.where("metadata.originalFilename")
                .is(fileUploadRequest.filename())
                .and("metadata.ownerId")
                .is(testUserId));
    // This is the only mongoTemplate stub needed for this test path
    when(mongoTemplate.exists(eq(expectedFilenameConflictQuery), eq("fs.files"))).thenReturn(true);
    // The mockMultipartFile.isEmpty() stub is removed because the filename conflict check happens
    // before isEmpty check.

    FileAlreadyExistsException exception =
        assertThrows(
            FileAlreadyExistsException.class,
            () -> {
              fileService.uploadFile(testUserId, mockMultipartFile, fileUploadRequest);
            });

    assertTrue(
        exception
            .getMessage()
            .contains(
                "Filename '" + fileUploadRequest.filename() + "' already exists for this user."));
    verify(gridFsTemplate, never()).store(any(), anyString(), anyString(), any(Document.class));
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq("fs.files"));
  }

  @Test
  void uploadFile_whenContentExistsForUser_shouldThrowIllegalArgumentException()
      throws IOException, NoSuchAlgorithmException {
    FileUploadRequest contentCheckRequest =
        new FileUploadRequest("anotherfile.txt", Visibility.PRIVATE, List.of("tag1"));
    ObjectId mockStoredObjectId = new ObjectId();

    when(mockMultipartFile.isEmpty()).thenReturn(false);
    when(mockMultipartFile.getInputStream())
        .thenReturn(new ByteArrayInputStream(testContent.getBytes()))
        .thenReturn(new ByteArrayInputStream(testContent.getBytes()));
    when(mockMultipartFile.getSize()).thenReturn((long) testContent.getBytes().length);

    Query filenameConflictQuery =
        Query.query(
            Criteria.where("metadata.originalFilename")
                .is(contentCheckRequest.filename())
                .and("metadata.ownerId")
                .is(testUserId));
    when(mongoTemplate.exists(eq(filenameConflictQuery), eq("fs.files"))).thenReturn(false);

    when(gridFsTemplate.store(
            any(InputStream.class),
            argThat(this::isValidUUID),
            anyString(),
            argThat(
                metadataDoc ->
                    testUserId.equals(metadataDoc.getString("ownerId"))
                        && contentCheckRequest
                            .filename()
                            .equals(metadataDoc.getString("originalFilename"))
                        && contentCheckRequest
                            .visibility()
                            .name()
                            .equals(metadataDoc.getString("visibility"))
                        && (long) testContent.getBytes().length == metadataDoc.getLong("size"))))
        .thenReturn(mockStoredObjectId);

    Query queryForHashUpdate = Query.query(Criteria.where("_id").is(mockStoredObjectId));
    when(mongoTemplate.updateFirst(
            eq(queryForHashUpdate),
            argThat(
                upd ->
                    ((Document) upd.getUpdateObject().get("$set")).containsKey("metadata.sha256")),
            eq("fs.files")))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("Simulated E11000 for hash"));

    FileAlreadyExistsException exception =
        assertThrows(
            FileAlreadyExistsException.class,
            () -> {
              fileService.uploadFile(testUserId, mockMultipartFile, contentCheckRequest);
            });

    assertTrue(
        exception.getMessage().startsWith("Content already exists for this user (hash conflict:"),
        "Exception message mismatch for content conflict");

    verify(gridFsTemplate)
        .store(
            any(InputStream.class), argThat(this::isValidUUID), anyString(), any(Document.class));
    verify(mongoTemplate)
        .updateFirst(
            eq(queryForHashUpdate),
            argThat(
                upd ->
                    ((Document) upd.getUpdateObject().get("$set")).containsKey("metadata.sha256")),
            eq("fs.files"));
    verify(gridFsTemplate, times(1))
        .delete(eq(Query.query(Criteria.where("_id").is(mockStoredObjectId))));
  }

  @Test
  void uploadFile_whenFilenameAndContentAreUnique_shouldSucceed()
      throws IOException, NoSuchAlgorithmException {
    ObjectId mockStoredObjectId = new ObjectId();

    when(mockMultipartFile.isEmpty()).thenReturn(false);
    when(mockMultipartFile.getInputStream())
        .thenReturn(new ByteArrayInputStream(testContent.getBytes()))
        .thenReturn(new ByteArrayInputStream(testContent.getBytes()));
    when(mockMultipartFile.getSize()).thenReturn((long) testContent.getBytes().length);

    Query filenameConflictQuery =
        Query.query(
            Criteria.where("metadata.originalFilename")
                .is(fileUploadRequest.filename())
                .and("metadata.ownerId")
                .is(testUserId));
    when(mongoTemplate.exists(eq(filenameConflictQuery), eq("fs.files"))).thenReturn(false);

    ArgumentCaptor<String> systemUuidCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Document> metadataCaptorForStore = ArgumentCaptor.forClass(Document.class);

    when(gridFsTemplate.store(
            any(InputStream.class),
            systemUuidCaptor.capture(),
            anyString(),
            metadataCaptorForStore.capture()))
        .thenReturn(mockStoredObjectId);

    UpdateResult mockUpdateResult = mock(UpdateResult.class);
    when(mockUpdateResult.getModifiedCount()).thenReturn(1L);
    when(mongoTemplate.updateFirst(
            eq(Query.query(Criteria.where("_id").is(mockStoredObjectId))),
            argThat(
                update -> {
                  Document setObject = update.getUpdateObject().get("$set", Document.class);
                  return setObject != null && setObject.containsKey("metadata.sha256");
                }),
            eq("fs.files")))
        .thenReturn(mockUpdateResult);

    FileResponse response =
        fileService.uploadFile(testUserId, mockMultipartFile, fileUploadRequest);

    assertNotNull(response);
    assertTrue(isValidUUID(response.id()), "Response ID should be a valid UUID");
    assertEquals(fileUploadRequest.filename(), response.filename());
    assertEquals(fileUploadRequest.visibility(), response.visibility());
    assertEquals(
        fileUploadRequest.tags().stream()
            .map(String::toLowerCase)
            .collect(java.util.stream.Collectors.toList()),
        response.tags());
    assertNotNull(response.uploadDate());
    assertNotNull(response.contentType());
    assertEquals(mockMultipartFile.getSize(), response.size());
    assertNotNull(response.downloadLink());
    assertTrue(response.downloadLink().startsWith("/api/v1/files/download/"));

    verify(mongoTemplate).exists(eq(filenameConflictQuery), eq("fs.files"));

    String capturedSystemUuid = systemUuidCaptor.getValue();
    assertTrue(isValidUUID(capturedSystemUuid));
    Document capturedMetadataForStore = metadataCaptorForStore.getValue();
    assertEquals(testUserId, capturedMetadataForStore.getString("ownerId"));
    assertEquals(
        fileUploadRequest.filename(), capturedMetadataForStore.getString("originalFilename"));
    assertEquals(
        fileUploadRequest.visibility().name(), capturedMetadataForStore.getString("visibility"));

    verify(mongoTemplate)
        .updateFirst(
            eq(Query.query(Criteria.where("_id").is(mockStoredObjectId))),
            argThat(
                update ->
                    ((Document) update.getUpdateObject().get("$set"))
                        .containsKey("metadata.sha256")),
            eq("fs.files"));
  }

  @Test
  void uploadFile_whenTooManyTags_shouldThrowIllegalArgumentException() {
    List<String> tooManyTags = List.of("tag1", "tag2", "tag3", "tag4", "tag5", "tag6");
    FileUploadRequest requestWithTooManyTags =
        new FileUploadRequest(testFilename, Visibility.PRIVATE, tooManyTags);

    InvalidRequestArgumentException exception =
        assertThrows(
            InvalidRequestArgumentException.class,
            () -> {
              fileService.uploadFile(testUserId, mockMultipartFile, requestWithTooManyTags);
            });

    assertTrue(exception.getMessage().contains("Cannot have more than 5 tags"));
  }

  @Test
  void uploadFile_whenFileIsEmpty_shouldThrowIllegalArgumentException() {
    when(mockMultipartFile.isEmpty()).thenReturn(true);

    InvalidRequestArgumentException exception =
        assertThrows(
            InvalidRequestArgumentException.class,
            () -> {
              fileService.uploadFile(testUserId, mockMultipartFile, fileUploadRequest);
            });

    assertTrue(exception.getMessage().contains("File is empty"));
  }

  @Test
  void downloadFile_whenTokenExists_shouldReturnGridFsResource() throws IOException {
    GridFSFile mockGridFSFile = mock(GridFSFile.class);
    GridFsResource mockGridFsResource = mock(GridFsResource.class);

    when(gridFsTemplate.findOne(any(Query.class))).thenReturn(mockGridFSFile);
    when(gridFsTemplate.getResource(mockGridFSFile)).thenReturn(mockGridFsResource);

    GridFsResource resultResource = fileService.downloadFile(testToken);

    assertNotNull(resultResource);
    assertSame(mockGridFsResource, resultResource);

    ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
    verify(gridFsTemplate).findOne(queryCaptor.capture());

    assertTrue(queryCaptor.getValue().getQueryObject().containsKey("metadata.token"));
    assertEquals(testToken, queryCaptor.getValue().getQueryObject().get("metadata.token"));
  }

  @Test
  void downloadFile_whenTokenDoesNotExist_shouldThrowException() {
    when(gridFsTemplate.findOne(any(Query.class))).thenReturn(null);

    Exception exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              fileService.downloadFile(testToken);
            });
    assertTrue(exception.getMessage().contains("File not found for token: " + testToken));
  }

  @Test
  void updateFileDetails_whenFileExistsAndUserOwnsItAndNewNameIsValid_shouldSucceed() {
    // testFileId is a UUID string (system ID)
    // newFilename is "new_document.txt"
    FileUpdateRequest updateRequest = new FileUpdateRequest(newFilename);

    // Initial state of the document that will be found
    Document initialMetadata =
        new Document("ownerId", testUserId)
            .append("originalFilename", "old_document.txt") // Original user filename
            .append("token", testToken)
            .append("visibility", Visibility.PRIVATE.name())
            .append("tags", List.of("tag1"));
    Document existingFileDoc =
        new Document("_id", new ObjectId()) // Mongo's internal _id
            .append("filename", testFileId) // System UUID
            .append("length", 100L)
            .append("contentType", "text/plain")
            .append("uploadDate", new Date())
            .append("metadata", initialMetadata);

    // State of the document AFTER the update (for the second findOne call)
    Document updatedMetadata =
        new Document(initialMetadata) // copy
            .append("originalFilename", newFilename); // new user filename
    Document updatedFileDoc =
        new Document(existingFileDoc) // copy
            .append("metadata", updatedMetadata);
    // system UUID (filename) does not change

    Query findBySystemUuidQuery = Query.query(Criteria.where("filename").is(testFileId));

    // Mock the first findOne call (finds the document to update)
    when(mongoTemplate.findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files")))
        .thenReturn(existingFileDoc) // First call returns current state
        .thenReturn(updatedFileDoc); // Second call (after update) returns updated state

    // Mock the conflict check for the new originalFilename (should return false - no conflict)
    Query conflictCheckQuery =
        Query.query(
            Criteria.where("metadata.originalFilename")
                .is(newFilename)
                .and("metadata.ownerId")
                .is(testUserId)
                .and("filename")
                .ne(testFileId) // Exclude self
            );
    when(mongoTemplate.exists(eq(conflictCheckQuery), eq("fs.files"))).thenReturn(false);

    // Mock the updateFirst operation
    UpdateResult mockUpdateResult = mock(UpdateResult.class);
    when(mockUpdateResult.getModifiedCount()).thenReturn(1L);
    when(mongoTemplate.updateFirst(
            eq(findBySystemUuidQuery),
            argThat(
                update -> {
                  Document setObject = update.getUpdateObject().get("$set", Document.class);
                  return newFilename.equals(setObject.getString("metadata.originalFilename"));
                }),
            eq("fs.files")))
        .thenReturn(mockUpdateResult);

    // Act
    FileResponse response = fileService.updateFileDetails(testUserId, testFileId, updateRequest);

    // Assert
    assertNotNull(response);
    assertEquals(testFileId, response.id(), "Response ID should be the system UUID");
    assertEquals(
        newFilename, response.filename(), "Response filename should be the new user-provided name");
    assertEquals(Visibility.PRIVATE, response.visibility());
    assertEquals(List.of("tag1"), response.tags());
    // Other fields can be asserted if necessary, e.g., from existingFileDoc or updatedFileDoc

    // Verify mock interactions
    verify(mongoTemplate, times(2))
        .findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files"));
    verify(mongoTemplate).exists(eq(conflictCheckQuery), eq("fs.files"));
    verify(mongoTemplate).updateFirst(eq(findBySystemUuidQuery), any(Update.class), eq("fs.files"));
  }

  @Test
  void updateFileDetails_whenFileDoesNotExistById_shouldThrowResourceNotFoundException() {
    FileUpdateRequest updateRequest = new FileUpdateRequest(newFilename);
    // testFileId is a UUID string (system ID)

    // Mock findOne by filename to return null
    Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));
    when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
        .thenReturn(null);

    ResourceNotFoundException exception =
        assertThrows(
            ResourceNotFoundException.class,
            () -> {
              fileService.updateFileDetails(testUserId, testFileId, updateRequest);
            });

    assertTrue(exception.getMessage().contains("File not found with id: " + testFileId));
    verify(mongoTemplate).findOne(eq(expectedQuery), eq(Document.class), eq("fs.files"));
  }

  @Test
  void updateFileDetails_whenNewFilenameConflicts_shouldThrowException() {
    String conflictingFilename = "i_already_exist.txt";
    FileUpdateRequest updateRequest = new FileUpdateRequest(conflictingFilename);
    // testFileId is a UUID string (system ID)

    // Document that will be found by testFileId
    Document initialMetadata =
        new Document("ownerId", testUserId)
            .append("originalFilename", "old_name.txt")
            .append("token", testToken)
            .append("visibility", Visibility.PRIVATE.name());
    Document existingFileDoc =
        new Document("_id", new ObjectId()) // Mongo's internal _id
            .append("filename", testFileId) // System UUID
            .append("metadata", initialMetadata);

    Query findBySystemUuidQuery = Query.query(Criteria.where("filename").is(testFileId));
    when(mongoTemplate.findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files")))
        .thenReturn(existingFileDoc);

    // Mock for the conflict check: mongoTemplate.exists returns true (CONFLICT!)
    Query conflictCheckQuery =
        Query.query(
            Criteria.where("metadata.originalFilename")
                .is(conflictingFilename)
                .and("metadata.ownerId")
                .is(testUserId)
                .and("filename")
                .ne(testFileId) // Exclude self from conflict check
            );
    when(mongoTemplate.exists(eq(conflictCheckQuery), eq("fs.files"))).thenReturn(true);

    FileAlreadyExistsException exception =
        assertThrows(
            FileAlreadyExistsException.class,
            () -> {
              fileService.updateFileDetails(testUserId, testFileId, updateRequest);
            });

    assertTrue(
        exception
            .getMessage()
            .contains("Filename '" + conflictingFilename + "' already exists for this user."));
    verify(mongoTemplate).findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files"));
    verify(mongoTemplate).exists(eq(conflictCheckQuery), eq("fs.files"));
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq("fs.files"));
  }

  @Test
  void updateFileDetails_whenUpdateOperationReportsNoModification_shouldThrowException() {
    String nonConflictingNewFilename = "unique_new_name.txt";
    FileUpdateRequest updateRequest = new FileUpdateRequest(nonConflictingNewFilename);
    // testFileId is a UUID string (system ID)

    Document initialMetadata =
        new Document("ownerId", testUserId)
            .append("originalFilename", "old_filename.txt")
            .append("token", testToken)
            .append("visibility", Visibility.PRIVATE.name());
    Document existingFileDoc =
        new Document("_id", new ObjectId())
            .append("filename", testFileId)
            .append("length", 123L)
            .append("contentType", "text/plain")
            .append("uploadDate", new Date())
            .append("metadata", initialMetadata);

    Query findBySystemUuidQuery = Query.query(Criteria.where("filename").is(testFileId));

    // Mock for the first findOne by filename
    when(mongoTemplate.findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files")))
        .thenReturn(existingFileDoc);

    // Mock for the conflict check: mongoTemplate.exists returns false (NO CONFLICT)
    Query conflictCheckQuery =
        Query.query(
            Criteria.where("metadata.originalFilename")
                .is(nonConflictingNewFilename)
                .and("metadata.ownerId")
                .is(testUserId)
                .and("filename")
                .ne(testFileId));
    when(mongoTemplate.exists(eq(conflictCheckQuery), eq("fs.files"))).thenReturn(false);

    // Mock for mongoTemplate.updateFirst to return 0 modifiedCount
    UpdateResult mockUpdateResult = mock(UpdateResult.class);
    when(mockUpdateResult.getModifiedCount()).thenReturn(0L);
    when(mongoTemplate.updateFirst(
            eq(findBySystemUuidQuery),
            argThat(
                update ->
                    nonConflictingNewFilename.equals(
                        update
                            .getUpdateObject()
                            .get("$set", Document.class)
                            .getString("metadata.originalFilename"))),
            eq("fs.files")))
        .thenReturn(mockUpdateResult);

    StorageException exception =
        assertThrows(
            StorageException.class,
            () -> {
              fileService.updateFileDetails(testUserId, testFileId, updateRequest);
            });
    // Verify the exact exception message from the service
    String expectedMessage =
        "File update for original filename failed for system ID: "
            + testFileId
            + ". Zero documents modified.";
    assertEquals(expectedMessage, exception.getMessage());

    // Verify calls
    verify(mongoTemplate)
        .findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files")); // Initial find
    verify(mongoTemplate).exists(eq(conflictCheckQuery), eq("fs.files")); // Conflict check
    verify(mongoTemplate)
        .updateFirst(
            eq(findBySystemUuidQuery), any(Update.class), eq("fs.files")); // Update attempt
    // Second findOne for response generation should NOT be called in this path
    verify(mongoTemplate, times(1)).findOne(any(Query.class), eq(Document.class), eq("fs.files"));
  }

  @Test
  void deleteFile_whenFileExistsAndUserOwnsIt_shouldDeleteFile() {
    // testFileId is a UUID string (system ID)

    Document metadata = new Document("ownerId", testUserId);
    Document fileDoc =
        new Document("_id", new ObjectId()) // Mongo's internal _id
            .append("filename", testFileId) // System UUID
            .append("metadata", metadata);

    Query findBySystemUuidQuery = Query.query(Criteria.where("filename").is(testFileId));

    // Mock the findOne call for ownership check
    when(mongoTemplate.findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files")))
        .thenReturn(fileDoc);

    // Act
    assertDoesNotThrow(() -> fileService.deleteFile(testUserId, testFileId));

    // Assert
    // Verify findOne was called
    verify(mongoTemplate).findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files"));
    // Verify delete was called with the same query based on filename (system UUID)
    verify(gridFsTemplate).delete(eq(findBySystemUuidQuery));
  }

  @Test
  void deleteFile_whenFileNotExists_shouldThrowResourceNotFoundException() {
    // testFileId is a UUID string (system ID)

    // Mock mongoTemplate.findOne to return null (file not found by filename)
    Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));
    when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
        .thenReturn(null);

    // Act & Assert
    ResourceNotFoundException exception =
        assertThrows(
            ResourceNotFoundException.class,
            () -> {
              fileService.deleteFile(testUserId, testFileId);
            });

    assertTrue(exception.getMessage().contains("File not found with id: " + testFileId));
    verify(mongoTemplate).findOne(eq(expectedQuery), eq(Document.class), eq("fs.files"));
    verify(gridFsTemplate, never()).delete(any(Query.class)); // Delete should not be called
  }

  @Test
  void deleteFile_whenUserNotOwner_shouldThrowUnauthorizedOperationException() {
    // testFileId is a UUID string (system ID)
    String actualOwnerId = "anotherOwner99"; // Different from testUserId ("user123")

    Document metadataOtherOwner = new Document("ownerId", actualOwnerId);
    Document fileDocOtherOwner =
        new Document("_id", new ObjectId())
            .append("filename", testFileId) // System UUID
            .append("metadata", metadataOtherOwner);

    Query findBySystemUuidQuery = Query.query(Criteria.where("filename").is(testFileId));
    when(mongoTemplate.findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files")))
        .thenReturn(fileDocOtherOwner);

    UnauthorizedOperationException exception =
        assertThrows(
            UnauthorizedOperationException.class,
            () -> {
              // testUserId attempts to delete otherUser99's file
              fileService.deleteFile(testUserId, testFileId);
            });

    assertTrue(
        exception
            .getMessage()
            .contains("User '" + testUserId + "' not authorized to delete fileId: " + testFileId));
    verify(mongoTemplate).findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files"));
    verify(gridFsTemplate, never()).delete(any(Query.class));
  }

  @Test
  void listFiles_whenNoUserIdAndNoTag_shouldListPublicFilesWithDefaultSort() {
    int pageNum = 0;
    int pageSize = 10;
    String sortByApi = "uploadDate"; // Service maps this to "uploadDate"
    String sortDir = "desc";

    // Prepare mock documents with system UUIDs and originalFilenames in metadata
    String systemId1 = UUID.randomUUID().toString();
    String originalFilename1 = "public1.txt";
    Document metadata1 =
        new Document("ownerId", "anotherUser")
            .append("visibility", Visibility.PUBLIC.name())
            .append("tags", List.of("public_tag"))
            .append("token", "token1")
            .append("originalFilename", originalFilename1);
    Document publicFileDoc1 =
        new Document("_id", new ObjectId())
            .append("filename", systemId1) // System UUID
            .append("uploadDate", new Date(System.currentTimeMillis() - 10000))
            .append("contentType", "text/plain")
            .append("length", 100L)
            .append("metadata", metadata1);

    String systemId2 = UUID.randomUUID().toString();
    String originalFilename2 = "public2.zip";
    Document metadata2 =
        new Document("ownerId", "user456")
            .append("visibility", Visibility.PUBLIC.name())
            .append("tags", List.of("general"))
            .append("token", "token2")
            .append("originalFilename", originalFilename2);
    Document publicFileDoc2 =
        new Document("_id", new ObjectId())
            .append("filename", systemId2) // System UUID
            .append("uploadDate", new Date(System.currentTimeMillis() - 5000))
            .append("contentType", "application/zip")
            .append("length", 2000L)
            .append("metadata", metadata2);

    // Assuming service sorts by uploadDate desc, publicFileDoc2 comes first
    List<Document> mockDocuments = Arrays.asList(publicFileDoc2, publicFileDoc1);

    // Use ArgumentCaptor for precise query verification
    ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);

    when(mongoTemplate.find(queryCaptor.capture(), eq(Document.class), eq("fs.files")))
        .thenReturn(mockDocuments);
    when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files")))
        .thenReturn((long) mockDocuments.size()); // Count can be less strict for now

    Page<FileResponse> resultPage =
        fileService.listFiles(null, null, sortByApi, sortDir, pageNum, pageSize);

    assertNotNull(resultPage);
    assertEquals(2, resultPage.getTotalElements());
    assertEquals(2, resultPage.getContent().size());

    // Verify FileResponse content (IDs are system UUIDs, filenames are originalFilenames)
    FileResponse resp2 = resultPage.getContent().get(0);
    assertEquals(systemId2, resp2.id());
    assertEquals(originalFilename2, resp2.filename()); // This was "public2.zip"
    assertEquals(Visibility.PUBLIC, resp2.visibility());

    FileResponse resp1 = resultPage.getContent().get(1);
    assertEquals(systemId1, resp1.id());
    assertEquals(originalFilename1, resp1.filename()); // This was "public1.txt"

    // Verify the query sent to mongoTemplate.find()
    Query capturedQuery = queryCaptor.getValue();
    Document queryObject = capturedQuery.getQueryObject();
    assertEquals(Visibility.PUBLIC.name(), queryObject.getString("metadata.visibility"));
    assertNull(queryObject.get("metadata.ownerId"));
    assertNull(queryObject.get("metadata.tags"));

    Document sortObject = capturedQuery.getSortObject();
    assertNotNull(sortObject);
    assertEquals(-1, sortObject.getInteger("uploadDate")); // desc for uploadDate
    assertEquals(pageSize, capturedQuery.getLimit());
    assertEquals((long) pageNum * pageSize, capturedQuery.getSkip());
  }

  @Test
  void listFiles_whenUserIdProvided_shouldListUserFiles() {
    int pageNum = 0;
    int pageSize = 5;
    String sortByApi = "filename"; // Service maps this to "metadata.originalFilename"
    String sortDir = "asc";

    // Prepare mock documents for testUserId
    String systemIdUser1 = UUID.randomUUID().toString();
    String originalFilenameUser1 = "userFileA.doc";
    Document metadataUser1 =
        new Document("ownerId", testUserId)
            .append("visibility", Visibility.PRIVATE.name())
            .append("tags", List.of("user_tag"))
            .append("token", "userToken1")
            .append("originalFilename", originalFilenameUser1);
    Document userFileDoc1 =
        new Document("_id", new ObjectId())
            .append("filename", systemIdUser1) // System UUID
            .append("uploadDate", new Date(System.currentTimeMillis() - 20000))
            .append("contentType", "application/msword")
            .append("length", 500L)
            .append("metadata", metadataUser1);

    String systemIdUser2 = UUID.randomUUID().toString();
    String originalFilenameUser2 = "userFileB.pdf";
    Document metadataUser2 =
        new Document("ownerId", testUserId)
            .append("visibility", Visibility.PUBLIC.name())
            .append("tags", List.of("user_tag", "shared"))
            .append("token", "userToken2")
            .append("originalFilename", originalFilenameUser2);
    Document userFileDoc2 =
        new Document("_id", new ObjectId())
            .append("filename", systemIdUser2) // System UUID
            .append("uploadDate", new Date(System.currentTimeMillis() - 15000))
            .append("contentType", "application/pdf")
            .append("length", 1500L)
            .append("metadata", metadataUser2);

    // Expected order: userFileA.doc, userFileB.pdf (sorted by originalFilename ASC)
    List<Document> mockUserDocuments = Arrays.asList(userFileDoc1, userFileDoc2);

    ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
    when(mongoTemplate.find(queryCaptor.capture(), eq(Document.class), eq("fs.files")))
        .thenReturn(mockUserDocuments);
    when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files")))
        .thenReturn((long) mockUserDocuments.size());

    Page<FileResponse> resultPage =
        fileService.listFiles(testUserId, null, sortByApi, sortDir, pageNum, pageSize);

    assertNotNull(resultPage);
    assertEquals(2, resultPage.getTotalElements());
    assertEquals(2, resultPage.getContent().size());

    FileResponse resp1 = resultPage.getContent().get(0);
    assertEquals(systemIdUser1, resp1.id());
    assertEquals(originalFilenameUser1, resp1.filename());

    FileResponse resp2 = resultPage.getContent().get(1);
    assertEquals(systemIdUser2, resp2.id());
    assertEquals(originalFilenameUser2, resp2.filename());

    // Verify the query
    Query capturedQuery = queryCaptor.getValue();
    Document queryObject = capturedQuery.getQueryObject();
    assertEquals(testUserId, queryObject.getString("metadata.ownerId"));
    assertNull(queryObject.get("metadata.tags"));

    Document sortObject = capturedQuery.getSortObject();
    assertNotNull(sortObject);
    assertEquals(1, sortObject.getInteger("metadata.originalFilename")); // ASC for originalFilename
    assertEquals(pageSize, capturedQuery.getLimit());
    assertEquals((long) pageNum * pageSize, capturedQuery.getSkip());
  }

  @Test
  void listFiles_whenTagProvided_shouldFilterByTagCaseInsensitively() {
    int pageNum = 0;
    int pageSize = 10;
    String filterTag = "Work"; // Mixed case
    String sortByApi = "uploadDate";
    String sortDir = "desc";

    String systemIdTagged = UUID.randomUUID().toString();
    String originalFilenameTagged = "work_document.docx";
    Document metadataTagged =
        new Document("ownerId", "user789")
            .append("visibility", Visibility.PUBLIC.name())
            .append("tags", List.of("personal", "work")) // Stored as lowercase "work"
            .append("token", "tagToken")
            .append("originalFilename", originalFilenameTagged);
    Document taggedFileDoc =
        new Document("_id", new ObjectId())
            .append("filename", systemIdTagged) // System UUID
            .append("uploadDate", new Date())
            .append(
                "contentType",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            .append("length", 3000L)
            .append("metadata", metadataTagged);

    List<Document> mockDocuments = List.of(taggedFileDoc);

    ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
    when(mongoTemplate.find(queryCaptor.capture(), eq(Document.class), eq("fs.files")))
        .thenReturn(mockDocuments);
    when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files")))
        .thenReturn((long) mockDocuments.size());

    Page<FileResponse> resultPage =
        fileService.listFiles(null, filterTag, sortByApi, sortDir, pageNum, pageSize);

    assertNotNull(resultPage);
    assertEquals(1, resultPage.getTotalElements());
    assertEquals(1, resultPage.getContent().size());

    FileResponse resp = resultPage.getContent().get(0);
    assertEquals(systemIdTagged, resp.id());
    assertEquals(originalFilenameTagged, resp.filename());
    assertTrue(resp.tags().contains("work"));
    assertEquals(Visibility.PUBLIC, resp.visibility());

    // Verify the query
    Query capturedQuery = queryCaptor.getValue();
    Document queryObject = capturedQuery.getQueryObject();
    assertEquals(Visibility.PUBLIC.name(), queryObject.getString("metadata.visibility"));
    assertEquals(
        filterTag.toLowerCase(),
        queryObject.getString("metadata.tags")); // Service converts filterTag to lowercase
    assertNull(queryObject.get("metadata.ownerId"));

    Document sortObject = capturedQuery.getSortObject();
    assertNotNull(sortObject);
    assertEquals(-1, sortObject.getInteger("uploadDate")); // desc for uploadDate
  }

  @Test
  void listFiles_whenSortByFilenameAsc_shouldReturnSortedResults() {
    int pageNum = 0;
    int pageSize = 10;
    String sortByApi = "filename"; // Service maps to "metadata.originalFilename"
    String sortDir = "asc";

    String systemIdZ = UUID.randomUUID().toString();
    String originalFilenameZ = "zeta.txt";
    Document metaZ =
        new Document("ownerId", "userZ")
            .append("visibility", Visibility.PUBLIC.name())
            .append("token", "tokenZ")
            .append("originalFilename", originalFilenameZ);
    Document docZ =
        new Document("_id", new ObjectId())
            .append("filename", systemIdZ)
            .append("uploadDate", new Date(System.currentTimeMillis() - 1000))
            .append("contentType", "text/plain")
            .append("length", 30L)
            .append("metadata", metaZ);

    String systemIdA = UUID.randomUUID().toString();
    String originalFilenameA = "alpha.txt";
    Document metaA =
        new Document("ownerId", "userA")
            .append("visibility", Visibility.PUBLIC.name())
            .append("token", "tokenA")
            .append("originalFilename", originalFilenameA);
    Document docA =
        new Document("_id", new ObjectId())
            .append("filename", systemIdA)
            .append("uploadDate", new Date(System.currentTimeMillis() - 2000))
            .append("contentType", "text/plain")
            .append("length", 10L)
            .append("metadata", metaA);

    String systemIdG = UUID.randomUUID().toString();
    String originalFilenameG = "gamma.txt";
    Document metaG =
        new Document("ownerId", "userG")
            .append("visibility", Visibility.PUBLIC.name())
            .append("token", "tokenG")
            .append("originalFilename", originalFilenameG);
    Document docG =
        new Document("_id", new ObjectId())
            .append("filename", systemIdG)
            .append("uploadDate", new Date(System.currentTimeMillis() - 3000))
            .append("contentType", "text/plain")
            .append("length", 20L)
            .append("metadata", metaG);

    List<Document> mockDocumentsSorted = Arrays.asList(docA, docG, docZ); // Expected order

    ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
    when(mongoTemplate.find(queryCaptor.capture(), eq(Document.class), eq("fs.files")))
        .thenReturn(mockDocumentsSorted);
    when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files")))
        .thenReturn((long) mockDocumentsSorted.size());

    Page<FileResponse> resultPage =
        fileService.listFiles(null, null, sortByApi, sortDir, pageNum, pageSize);

    assertNotNull(resultPage);
    assertEquals(3, resultPage.getTotalElements());
    assertEquals(3, resultPage.getContent().size());
    assertEquals(originalFilenameA, resultPage.getContent().get(0).filename());
    assertEquals(systemIdA, resultPage.getContent().get(0).id());
    assertEquals(originalFilenameG, resultPage.getContent().get(1).filename());
    assertEquals(systemIdG, resultPage.getContent().get(1).id());
    assertEquals(originalFilenameZ, resultPage.getContent().get(2).filename());
    assertEquals(systemIdZ, resultPage.getContent().get(2).id());

    // Verify Query
    Query capturedQuery = queryCaptor.getValue();
    Document queryObject = capturedQuery.getQueryObject();
    assertEquals(Visibility.PUBLIC.name(), queryObject.getString("metadata.visibility"));
    assertNull(queryObject.get("metadata.ownerId"));
    assertNull(queryObject.get("metadata.tags"));

    Document sortObject = capturedQuery.getSortObject();
    assertNotNull(sortObject);
    assertEquals(
        1, sortObject.getInteger("metadata.originalFilename")); // ASC for metadata.originalFilename
  }

  @Test
  void listFiles_whenSortBySizeDesc_shouldReturnSortedResults() {
    int pageNum = 0;
    int pageSize = 10;
    String sortByApi = "size";
    String sortDir = "desc";
    String sortFieldInDb = "length"; // API "size" maps to DB "length"

    String systemIdS = UUID.randomUUID().toString();
    String originalFilenameS = "small.txt";
    long sizeS = 50L;
    Document metaS =
        new Document("ownerId", "userS")
            .append("visibility", Visibility.PUBLIC.name())
            .append("token", "tokenS")
            .append("originalFilename", originalFilenameS);
    Document docS =
        new Document("_id", new ObjectId())
            .append("filename", systemIdS)
            .append("uploadDate", new Date())
            .append("contentType", "text/plain")
            .append("length", sizeS)
            .append("metadata", metaS);

    String systemIdL = UUID.randomUUID().toString();
    String originalFilenameL = "large.doc";
    long sizeL = 5000L;
    Document metaL =
        new Document("ownerId", "userL")
            .append("visibility", Visibility.PUBLIC.name())
            .append("token", "tokenL")
            .append("originalFilename", originalFilenameL);
    Document docL =
        new Document("_id", new ObjectId())
            .append("filename", systemIdL)
            .append("uploadDate", new Date())
            .append("contentType", "application/msword")
            .append("length", sizeL)
            .append("metadata", metaL);

    String systemIdM = UUID.randomUUID().toString();
    String originalFilenameM = "medium.pdf";
    long sizeM = 500L;
    Document metaM =
        new Document("ownerId", "userM")
            .append("visibility", Visibility.PUBLIC.name())
            .append("token", "tokenM")
            .append("originalFilename", originalFilenameM);
    Document docM =
        new Document("_id", new ObjectId())
            .append("filename", systemIdM)
            .append("uploadDate", new Date())
            .append("contentType", "application/pdf")
            .append("length", sizeM)
            .append("metadata", metaM);

    List<Document> mockDocumentsSorted =
        Arrays.asList(docL, docM, docS); // Expected order by size DESC

    ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
    when(mongoTemplate.find(queryCaptor.capture(), eq(Document.class), eq("fs.files")))
        .thenReturn(mockDocumentsSorted);
    when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files")))
        .thenReturn((long) mockDocumentsSorted.size());

    Page<FileResponse> resultPage =
        fileService.listFiles(null, null, sortByApi, sortDir, pageNum, pageSize);

    assertNotNull(resultPage);
    assertEquals(3, resultPage.getTotalElements());
    assertEquals(3, resultPage.getContent().size());

    assertEquals(originalFilenameL, resultPage.getContent().get(0).filename());
    assertEquals(systemIdL, resultPage.getContent().get(0).id());
    assertEquals(sizeL, resultPage.getContent().get(0).size());

    assertEquals(originalFilenameM, resultPage.getContent().get(1).filename());
    assertEquals(systemIdM, resultPage.getContent().get(1).id());
    assertEquals(sizeM, resultPage.getContent().get(1).size());

    assertEquals(originalFilenameS, resultPage.getContent().get(2).filename());
    assertEquals(systemIdS, resultPage.getContent().get(2).id());
    assertEquals(sizeS, resultPage.getContent().get(2).size());

    // Verify Query
    Query capturedQuery = queryCaptor.getValue();
    Document queryObject = capturedQuery.getQueryObject();
    assertEquals(Visibility.PUBLIC.name(), queryObject.getString("metadata.visibility"));
    assertNull(queryObject.get("metadata.ownerId"));
    assertNull(queryObject.get("metadata.tags"));

    Document sortObject = capturedQuery.getSortObject();
    assertNotNull(sortObject);
    assertEquals(-1, sortObject.getInteger(sortFieldInDb)); // DESC for length
  }

  @Test
  void listFiles_whenNoFilesMatchCriteria_shouldReturnEmptyPage() {
    int pageNum = 0;
    int pageSize = 10;
    String filterTag = "nonexistenttag";
    String sortBy = "uploadDate";
    String sortDir = "desc";

    // Mock for mongoTemplate.find() to return empty list
    when(mongoTemplate.find(
            argThat(
                new ArgumentMatcher<Query>() {
                  @Override
                  public boolean matches(Query argument) {
                    if (argument == null || argument.getQueryObject() == null) return false;
                    Document queryObject = argument.getQueryObject();
                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    boolean correctVisibility = Visibility.PUBLIC.name().equals(visibilityInQuery);
                    String tagInQuery = queryObject.getString("metadata.tags");
                    boolean correctTag = filterTag.toLowerCase().equals(tagInQuery);
                    return correctVisibility && correctTag;
                  }
                }),
            eq(Document.class),
            eq("fs.files")))
        .thenReturn(Collections.emptyList());

    // Mock for mongoTemplate.count() to return 0
    when(mongoTemplate.count(
            argThat(
                new ArgumentMatcher<Query>() {
                  @Override
                  public boolean matches(Query argument) {
                    if (argument == null || argument.getQueryObject() == null) return false;
                    Document queryObject = argument.getQueryObject();
                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    boolean correctVisibility = Visibility.PUBLIC.name().equals(visibilityInQuery);
                    String tagInQuery = queryObject.getString("metadata.tags");
                    boolean correctTag = filterTag.toLowerCase().equals(tagInQuery);
                    return correctVisibility && correctTag;
                  }
                }),
            eq(Document.class),
            eq("fs.files")))
        .thenReturn(0L);

    Page<FileResponse> resultPage =
        fileService.listFiles(null, filterTag, sortBy, sortDir, pageNum, pageSize);

    assertNotNull(resultPage);
    assertEquals(0, resultPage.getTotalElements());
    assertTrue(resultPage.getContent().isEmpty());
    assertEquals(pageNum, resultPage.getNumber());
    assertEquals(pageSize, resultPage.getSize()); // Pageable's size
  }

  @Test
  void listFiles_whenInvalidSortByField_shouldThrowIllegalArgumentException() {
    String invalidSortField = "nonExistentSortField";

    // The exception should be thrown by mapSortField before any DB interaction,
    // so no mongoTemplate mocking is strictly needed for this specific exception path.

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              fileService.listFiles(testUserId, null, invalidSortField, "asc", 0, 10);
            });
    assertTrue(exception.getMessage().contains("Invalid sortBy field: " + invalidSortField));
  }

  @Test
  void updateFileDetails_whenUserNotOwner_shouldThrowUnauthorizedOperationException() {
    FileUpdateRequest updateRequest = new FileUpdateRequest(newFilename);
    // testFileId is a UUID string (system ID)
    String actualOwnerId = "anotherOwner123"; // Different from testUserId ("user123")

    Document metadataOtherOwner =
        new Document("ownerId", actualOwnerId).append("originalFilename", "some_file.txt");
    Document fileDocOtherOwner =
        new Document("_id", new ObjectId())
            .append("filename", testFileId) // System UUID
            .append("metadata", metadataOtherOwner);

    Query findBySystemUuidQuery = Query.query(Criteria.where("filename").is(testFileId));
    when(mongoTemplate.findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files")))
        .thenReturn(fileDocOtherOwner);

    UnauthorizedOperationException exception =
        assertThrows(
            UnauthorizedOperationException.class,
            () -> {
              // testUserId attempts to update a file owned by actualOwnerId
              fileService.updateFileDetails(testUserId, testFileId, updateRequest);
            });

    assertTrue(
        exception
            .getMessage()
            .contains("User '" + testUserId + "' not authorized to update fileId: " + testFileId));

    verify(mongoTemplate).findOne(eq(findBySystemUuidQuery), eq(Document.class), eq("fs.files"));
    verify(mongoTemplate, never()).exists(any(Query.class), eq("fs.files"));
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq("fs.files"));
  }
}
