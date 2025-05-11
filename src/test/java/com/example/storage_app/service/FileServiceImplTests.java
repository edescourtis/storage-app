package com.example.storage_app.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.tika.io.LookaheadInputStream;
import org.bson.Document; // For mutable list of tags
import org.bson.types.ObjectId; // For token generation in response construction
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.exception.FileAlreadyExistsException;
import com.example.storage_app.exception.InvalidRequestArgumentException;
import com.example.storage_app.exception.ResourceNotFoundException;
import com.example.storage_app.exception.StorageException;
import com.example.storage_app.exception.UnauthorizedOperationException;
import com.example.storage_app.model.Visibility;
import com.example.storage_app.util.MimeUtil;
import com.mongodb.client.result.UpdateResult;


@ExtendWith(MockitoExtension.class)
class FileServiceImplTests {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private GridFsTemplate gridFsTemplate;

    @Mock
    private MultipartFile mockFile; 

    @InjectMocks
    private FileServiceImpl fileService;

    private String testUserId;
    private String testFileId;
    private FileUpdateRequest updateRequest;
    private Document existingFileDoc;
    private Document metadataDoc;
    private FileUploadRequest defaultUploadRequest;

    private static final Pattern UUID_REGEX =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private boolean isValidUUID(String s) {
        if (s == null) return false;
        return UUID_REGEX.matcher(s).matches();
    }

    @BeforeEach
    void setUp() {
        testUserId = "user123";
        testFileId = UUID.randomUUID().toString();
        updateRequest = new FileUpdateRequest("new_user_filename.txt");

        metadataDoc = new Document()
                .append("ownerId", testUserId)
                .append("visibility", Visibility.PRIVATE.name())
                .append("tags", Collections.singletonList("tag1"))
                .append("token", "test-token")
                .append("originalFilename", "old_user_filename.txt");

        existingFileDoc = new Document()
                .append("_id", new ObjectId())
                .append("filename", testFileId)
                .append("length", 1024L)
                .append("contentType", "text/plain")
                .append("uploadDate", new Date())
                .append("metadata", metadataDoc);
        
        defaultUploadRequest = new FileUploadRequest(
            "user_requested_file.txt", 
            Visibility.PRIVATE,
            List.of("tag1", "tag2")
        );

        lenient().when(mockFile.getOriginalFilename()).thenReturn("original_multipart_filename.txt");
        lenient().when(mockFile.isEmpty()).thenReturn(false);
        lenient().when(mockFile.getSize()).thenReturn(12L); 

        ObjectId mockMongoDbIdAfterStore = new ObjectId();
        lenient().when(gridFsTemplate.store(any(InputStream.class), anyString(), anyString(), any(Document.class)))
               .thenReturn(mockMongoDbIdAfterStore);
        
        Document storedDocForPostStoreFetch = new Document()
            .append("_id", mockMongoDbIdAfterStore)
            .append("filename", "mock-uuid-after-store")
            .append("length", 12L)
            .append("contentType", "application/octet-stream")
            .append("uploadDate", new Date())
            .append("metadata", new Document()
                .append("ownerId", testUserId)
                .append("originalFilename", defaultUploadRequest.filename())
                .append("sha256", "mocksha256value")
                .append("visibility", defaultUploadRequest.visibility().name())
                .append("tags", defaultUploadRequest.tags())
                .append("token", "mock-token-after-store")
                .append("userContentType", "text/plain")
                .append("detectedContentType", "application/octet-stream")
                .append("originalFileSize", 12L)
            );
        lenient().when(mongoTemplate.findOne(eq(Query.query(Criteria.where("_id").is(mockMongoDbIdAfterStore))), eq(Document.class), eq("fs.files")))
            .thenReturn(storedDocForPostStoreFetch);

        UpdateResult mockUpdateResult = mock(UpdateResult.class);
        lenient().when(mockUpdateResult.getModifiedCount()).thenReturn(1L);
        lenient().when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("fs.files"))).thenReturn(mockUpdateResult);

        lenient().when(mongoTemplate.exists(any(Query.class), eq("fs.files"))).thenReturn(false);
    }

    // Tests for uploadFile start here
    private void mockMimeDetect(MockedStatic<MimeUtil> mockedMimeUtil, String contentType, InputStream inputStream) throws IOException {
        LookaheadInputStream lookaheadInputStream = new LookaheadInputStream(inputStream, 64 * 1024); 
        MimeUtil.Detected detected = new MimeUtil.Detected(lookaheadInputStream, contentType); 
        mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
    }

    @Test
    void uploadFile_Success_WithGivenFilename() throws Exception {
        try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
            InputStream mockInputStream = new ByteArrayInputStream("test content".getBytes());
            MimeUtil.Detected detected = new MimeUtil.Detected(new LookaheadInputStream(mockInputStream, 64 * 1024), "text/plain");
            mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
            
            when(mockFile.getInputStream())
                .thenReturn(new ByteArrayInputStream("test content".getBytes())) 
                .thenReturn(new ByteArrayInputStream("test content".getBytes())) 
                .thenReturn(new ByteArrayInputStream("test content".getBytes()));

            Query userOrigFilenameConflictQuery = Query.query(Criteria.where("metadata.originalFilename").is(defaultUploadRequest.filename()).and("metadata.ownerId").is(testUserId));
            when(mongoTemplate.exists(eq(userOrigFilenameConflictQuery), eq("fs.files"))).thenReturn(false);
            
            FileResponse response = fileService.uploadFile(testUserId, mockFile, defaultUploadRequest);

            assertNotNull(response);
            assertTrue(isValidUUID(response.id()));
            assertEquals(defaultUploadRequest.filename(), response.filename());
            assertEquals(defaultUploadRequest.visibility(), response.visibility());
            assertNotNull(response.downloadLink());
            assertTrue(response.downloadLink().contains("/api/v1/files/download/"));
            
            verify(mongoTemplate).exists(eq(userOrigFilenameConflictQuery), eq("fs.files"));

            verify(gridFsTemplate).store(
                any(InputStream.class), 
                argThat(this::isValidUUID), 
                eq("text/plain"),
                argThat(metadataDocInStore -> 
                    defaultUploadRequest.filename().equals(metadataDocInStore.getString("originalFilename")) &&
                    testUserId.equals(metadataDocInStore.getString("ownerId")) &&
                    defaultUploadRequest.visibility().name().equals(metadataDocInStore.getString("visibility")) &&
                    (defaultUploadRequest.tags().stream().map(String::toLowerCase).collect(Collectors.toList()))
                        .equals(metadataDocInStore.getList("tags", String.class)) &&
                    metadataDocInStore.containsKey("token") &&
                    metadataDocInStore.containsKey("uploadDate") &&
                    "text/plain".equals(metadataDocInStore.getString("contentType"))
                )
            );
            
            verify(mongoTemplate).updateFirst(
                argThat(q -> q.getQueryObject().containsKey("_id")), 
                argThat(upd -> {
                    Document setObject = (Document) upd.getUpdateObject().get("$set");
                    return setObject.containsKey("metadata.sha256");
                }),
                eq("fs.files")
            );
        }
    }

    @Test
    void uploadFile_Success_UsesOriginalFilename_WhenRequestFilenameNull() throws Exception {
        try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
            InputStream mockInputStream = new ByteArrayInputStream("test content".getBytes());
            MimeUtil.Detected detected = new MimeUtil.Detected(new LookaheadInputStream(mockInputStream, 64*1024), "text/plain");
            mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
            when(mockFile.getInputStream())
                .thenReturn(new ByteArrayInputStream("test content".getBytes())) // Tika
                .thenReturn(new ByteArrayInputStream("test content".getBytes())) // Hashing
                .thenReturn(new ByteArrayInputStream("test content".getBytes())); // Store
            
            String multipartOriginalFilename = mockFile.getOriginalFilename(); 
            FileUploadRequest requestWithNullFilename = new FileUploadRequest(null, Visibility.PUBLIC, List.of("t1"));

            Query userOrigFilenameConflictQuery = Query.query(Criteria.where("metadata.originalFilename").is(multipartOriginalFilename).and("metadata.ownerId").is(testUserId));
            when(mongoTemplate.exists(eq(userOrigFilenameConflictQuery), eq("fs.files"))).thenReturn(false);

            FileResponse response = fileService.uploadFile(testUserId, mockFile, requestWithNullFilename);
            
            assertNotNull(response);
            assertTrue(isValidUUID(response.id()), "Response ID should be a valid UUID");
            assertEquals(multipartOriginalFilename, response.filename());
            verify(gridFsTemplate).store(any(InputStream.class), 
                                         argThat(this::isValidUUID), 
                                         any(String.class), 
                                         argThat(doc -> multipartOriginalFilename.equals(doc.get("originalFilename"))));
        }
    }

    @Test
    void uploadFile_Success_UsesOriginalFilename_WhenRequestFilenameBlank() throws Exception {
         try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
            InputStream mockInputStream = new ByteArrayInputStream("test content".getBytes());
            MimeUtil.Detected detected = new MimeUtil.Detected(new LookaheadInputStream(mockInputStream, 64*1024), "text/plain");
            mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
            when(mockFile.getInputStream())
                .thenReturn(new ByteArrayInputStream("test content".getBytes())) // Tika
                .thenReturn(new ByteArrayInputStream("test content".getBytes())) // Hashing
                .thenReturn(new ByteArrayInputStream("test content".getBytes())); // Store
            
            String multipartOriginalFilename = mockFile.getOriginalFilename(); 
            FileUploadRequest requestWithBlankFilename = new FileUploadRequest("   ", Visibility.PUBLIC, List.of("t1"));

            Query userOrigFilenameConflictQuery = Query.query(Criteria.where("metadata.originalFilename").is(multipartOriginalFilename).and("metadata.ownerId").is(testUserId));
            when(mongoTemplate.exists(eq(userOrigFilenameConflictQuery), eq("fs.files"))).thenReturn(false);

            FileResponse response = fileService.uploadFile(testUserId, mockFile, requestWithBlankFilename);

            assertNotNull(response);
            assertTrue(isValidUUID(response.id()), "Response ID should be a valid UUID");
            assertEquals(multipartOriginalFilename, response.filename());
            verify(gridFsTemplate).store(any(InputStream.class), 
                                         argThat(this::isValidUUID), 
                                         any(String.class), 
                                         argThat(doc -> multipartOriginalFilename.equals(doc.get("originalFilename"))));
        }
    }

    @Test
    void uploadFile_ThrowsInvalidRequestArgument_WhenTagsExceedMax() {
        List<String> tooManyTags = List.of("1", "2", "3", "4", "5", "6");
        FileUploadRequest requestWithTooManyTags = new FileUploadRequest("file.txt", Visibility.PUBLIC, tooManyTags);
            
        InvalidRequestArgumentException ex = assertThrows(InvalidRequestArgumentException.class, () -> {
            fileService.uploadFile(testUserId, mockFile, requestWithTooManyTags);
        });
        assertTrue(ex.getMessage().contains("Cannot have more than 5 tags"));
    }

    @Test
    void uploadFile_ThrowsInvalidRequestArgument_WhenFileIsEmpty() {
        when(mockFile.isEmpty()).thenReturn(true);
        InvalidRequestArgumentException ex = assertThrows(InvalidRequestArgumentException.class, () -> {
            fileService.uploadFile(testUserId, mockFile, defaultUploadRequest);
        });
        assertEquals("File is empty", ex.getMessage());
    }

    @Test
    void uploadFile_HandlesNullOriginalTagsInRequest() throws Exception {
        try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
            InputStream mockInputStream = new ByteArrayInputStream("test content".getBytes());
            mockMimeDetect(mockedMimeUtil, "text/plain", mockInputStream);
            when(mockFile.getInputStream()).thenReturn(new ByteArrayInputStream("test content".getBytes()));
            FileUploadRequest requestWithNullTags = new FileUploadRequest("file.txt", Visibility.PUBLIC, null);

            when(mongoTemplate.exists(any(Query.class), eq("fs.files"))).thenReturn(false);

            FileResponse response = fileService.uploadFile(testUserId, mockFile, requestWithNullTags);
            assertNotNull(response);
            assertTrue(response.tags().isEmpty()); 
        }
    }
    
    @Test
    void uploadFile_HandlesNullTagWithinOriginalTagsList() throws Exception {
        try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
            InputStream mockInputStream = new ByteArrayInputStream("test content".getBytes());
            mockMimeDetect(mockedMimeUtil, "text/plain", mockInputStream);
            when(mockFile.getInputStream()).thenReturn(new ByteArrayInputStream("test content".getBytes()));
            List<String> tagsWithNull = new ArrayList<>();
            tagsWithNull.add("tag1");
            tagsWithNull.add(null); 
            tagsWithNull.add("tag2");
            FileUploadRequest requestWithNullInTags = new FileUploadRequest("file.txt", Visibility.PUBLIC, tagsWithNull);

            when(mongoTemplate.exists(any(Query.class), eq("fs.files"))).thenReturn(false);

            FileResponse response = fileService.uploadFile(testUserId, mockFile, requestWithNullInTags);
            assertNotNull(response);
            assertEquals(2, response.tags().size()); 
            assertTrue(response.tags().containsAll(List.of("tag1", "tag2")));
        }
    }

    @Test
    void uploadFile_ThrowsFileAlreadyExists_WhenFilenameConflict() throws Exception {
        try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
            MimeUtil.Detected detected = new MimeUtil.Detected(new LookaheadInputStream(new ByteArrayInputStream("test content".getBytes()), 64*1024), "text/plain");
            mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
            // mockFile.getInputStream() stub is not strictly needed here because if filename conflict occurs, getInputStream is never called by the service.

            Query userOrigFilenameConflictQuery = Query.query(
                Criteria.where("metadata.originalFilename").is(defaultUploadRequest.filename())
                        .and("metadata.ownerId").is(testUserId)
            );
            when(mongoTemplate.exists(eq(userOrigFilenameConflictQuery), eq("fs.files"))).thenReturn(true);
            
            FileAlreadyExistsException ex = assertThrows(FileAlreadyExistsException.class, () -> {
                fileService.uploadFile(testUserId, mockFile, defaultUploadRequest);
            });
            String expectedMessage = "Filename '" + defaultUploadRequest.filename() + "' already exists for this user.";
            assertEquals(expectedMessage, ex.getMessage(), "Exception message mismatch for filename conflict");
            verify(gridFsTemplate, never()).store(any(), any(), any(), any());
        }
    }

    @Test
    void uploadFile_ThrowsFileAlreadyExists_WhenContentConflict() throws Exception {
        try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
            String testContent = "test content with specific hash";
            String expectedMimeType = "text/plain";
            ObjectId storedObjectId = new ObjectId(); // ObjectId returned by the initial store

            MimeUtil.Detected detected = new MimeUtil.Detected(new LookaheadInputStream(new ByteArrayInputStream(testContent.getBytes()), 64*1024), expectedMimeType);
            mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenReturn(detected);
            
            when(mockFile.getInputStream())
                .thenReturn(new ByteArrayInputStream(testContent.getBytes())); 
            when(mockFile.getSize()).thenReturn((long)testContent.getBytes().length);

            Query userOrigFilenameConflictQuery = Query.query(
                Criteria.where("metadata.originalFilename").is(defaultUploadRequest.filename())
                        .and("metadata.ownerId").is(testUserId)
            );
            when(mongoTemplate.exists(eq(userOrigFilenameConflictQuery), eq("fs.files"))).thenReturn(false);

            when(gridFsTemplate.store(
                any(InputStream.class), 
                argThat(this::isValidUUID), 
                eq(expectedMimeType), 
                argThat(meta -> defaultUploadRequest.visibility().name().equals(meta.getString("visibility")) && 
                                 defaultUploadRequest.filename().equals(meta.getString("originalFilename"))
                )
            )).thenReturn(storedObjectId);

            Query queryForHashUpdate = Query.query(Criteria.where("_id").is(storedObjectId));
            when(mongoTemplate.updateFirst(
                eq(queryForHashUpdate), 
                argThat(upd -> ((Document) upd.getUpdateObject().get("$set")).containsKey("metadata.sha256")), 
                eq("fs.files")
            )).thenThrow(new org.springframework.dao.DuplicateKeyException("Simulated E11000 for hash"));
            
            // Mock the delete call that should now happen manually
            doNothing().when(gridFsTemplate).delete(eq(Query.query(Criteria.where("_id").is(storedObjectId))));
            
            FileAlreadyExistsException ex = assertThrows(FileAlreadyExistsException.class, () -> {
                fileService.uploadFile(testUserId, mockFile, defaultUploadRequest);
            });
            assertTrue(ex.getMessage().startsWith("Content already exists for this user (hash conflict:"), "Exception message mismatch");
            
            verify(gridFsTemplate, times(1)).store(any(InputStream.class), anyString(), anyString(), any(Document.class));
            verify(mongoTemplate, times(1)).updateFirst(eq(queryForHashUpdate), any(Update.class), eq("fs.files"));
            // Verify that gridFsTemplate.delete() IS called by the service for manual cleanup
            verify(gridFsTemplate, times(1)).delete(eq(Query.query(Criteria.where("_id").is(storedObjectId))));
        }
    }
    
    @Test
    void uploadFile_MimeDetectionFails_PropagatesIOException() throws Exception {
        try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
            IOException ioException = new IOException("MIME detect error");
            mockedMimeUtil.when(() -> MimeUtil.detect(any(InputStream.class))).thenThrow(ioException);
            when(mockFile.getInputStream()).thenReturn(new ByteArrayInputStream("test".getBytes()));

            IOException thrown = assertThrows(IOException.class, () -> {
                fileService.uploadFile(testUserId, mockFile, defaultUploadRequest);
            });
            assertEquals(ioException.getMessage(), thrown.getMessage());
        }
    }
    
    @Test
    void uploadFile_OriginalFilenameIsNullAndRequestFilenameIsNull_LeadsToStoreError() throws Exception {
        when(mockFile.getOriginalFilename()).thenReturn(null); 
        FileUploadRequest requestWithNullFilename = new FileUploadRequest(null, Visibility.PRIVATE, Collections.emptyList());

        try (MockedStatic<MimeUtil> mockedMimeUtil = mockStatic(MimeUtil.class)) {
            InvalidRequestArgumentException ex = assertThrows(InvalidRequestArgumentException.class, () -> {
                fileService.uploadFile(testUserId, mockFile, requestWithNullFilename);
            });
            assertEquals("Filename cannot be empty.", ex.getMessage());
            
            verify(gridFsTemplate, never()).store(any(InputStream.class), anyString(), anyString(), any(Document.class));
            verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq("fs.files"));
            mockedMimeUtil.verify(() -> MimeUtil.detect(any(InputStream.class)), never());
        }
    }

    // End of uploadFile tests

    @Test
    void updateFileDetails_Success() {
        // Input fileId for the service is the systemFilenameUUID (testFileId)
        Query findBySystemUUIDQuery = Query.query(Criteria.where("filename").is(testFileId));

        // This is the document that would be returned by the update operation reflecting the change
        Document metadataAfterUpdate = new Document(metadataDoc); // copy from setUp metadata
        metadataAfterUpdate.put("originalFilename", updateRequest.newFilename()); // apply the update
        
        Document fileDocAfterUpdate = new Document(existingFileDoc); // copy from setUp existingFileDoc
        fileDocAfterUpdate.put("metadata", metadataAfterUpdate); // put updated metadata into it
        // Ensure filename (system UUID) remains the same, _id also remains same as it's not changed by this op
        fileDocAfterUpdate.put("filename", testFileId); 
        // _id from existingFileDoc is already set in setUp, so it carries over in the copy

        // Mock sequence for findOne: 
        // 1. Initial fetch by service returns the original document state
        // 2. Fetch after successful update returns the document with updated metadata
        when(mongoTemplate.findOne(eq(findBySystemUUIDQuery), eq(Document.class), eq("fs.files")))
                .thenReturn(existingFileDoc)          // First call (before update)
                .thenReturn(fileDocAfterUpdate);       // Second call (after update)
        
        // Mock conflict check for the new original filename to return false (no conflict)
        Query conflictCheckQuery = Query.query(
            Criteria.where("metadata.originalFilename").is(updateRequest.newFilename())
                    .and("metadata.ownerId").is(testUserId)
                    .and("filename").ne(testFileId) // Crucial: don't conflict with self
        );
        when(mongoTemplate.exists(eq(conflictCheckQuery), eq("fs.files"))).thenReturn(false);

        // Mock the update operation itself to succeed
        UpdateResult mockUpdateResult = mock(UpdateResult.class);
        when(mockUpdateResult.getModifiedCount()).thenReturn(1L);
        // Verify the update operation targets the correct document and sets the correct metadata field
        when(mongoTemplate.updateFirst(
            eq(findBySystemUUIDQuery), 
            argThat(update -> {
                Document setObject = (Document) update.getUpdateObject().get("$set");
                return updateRequest.newFilename().equals(setObject.getString("metadata.originalFilename"));
            }), 
            eq("fs.files")))
        .thenReturn(mockUpdateResult);

        FileResponse response = fileService.updateFileDetails(testUserId, testFileId, updateRequest);

        assertNotNull(response);
        assertEquals(testFileId, response.id(), "Response ID should be the systemFilenameUUID");
        assertEquals(updateRequest.newFilename(), response.filename(), "Response filename should be the new user-provided name");

        // Verify the calls
        verify(mongoTemplate, times(2)).findOne(eq(findBySystemUUIDQuery), eq(Document.class), eq("fs.files")); // Called once before update, once after
        verify(mongoTemplate).exists(eq(conflictCheckQuery), eq("fs.files"));
        verify(mongoTemplate).updateFirst(
            eq(findBySystemUUIDQuery), 
            argThat(update -> {
                Document setObject = (Document) update.getUpdateObject().get("$set");
                return updateRequest.newFilename().equals(setObject.getString("metadata.originalFilename"));
            }), 
            eq("fs.files"));
    }

    @Test
    void updateFileDetails_FileNotFound() {
        // testFileId is the systemFilenameUUID (String)
        Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));
        when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
                .thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> {
            fileService.updateFileDetails(testUserId, testFileId, updateRequest); // testFileId is already the UUID string
        });

        verify(mongoTemplate).findOne(eq(expectedQuery), eq(Document.class), eq("fs.files"));
    }

    @Test
    void updateFileDetails_MetadataMissing() {
        // testFileId is systemFilenameUUID. existingFileDoc has filename = testFileId.
        Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));
        Document docWithoutMetadata = new Document(existingFileDoc); // Make a copy
        docWithoutMetadata.remove("metadata"); 
        // Ensure this doc still has the correct filename for the query to match
        docWithoutMetadata.put("filename", testFileId); 
        docWithoutMetadata.put("_id", new ObjectId()); // Ensure it has an _id like a real doc

        when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
                .thenReturn(docWithoutMetadata);

        Exception ex = assertThrows(StorageException.class, () -> {
            fileService.updateFileDetails(testUserId, testFileId, updateRequest);
        });
        assertEquals("File metadata is missing for fileId: " + testFileId, ex.getMessage());
        verify(mongoTemplate).findOne(eq(expectedQuery), eq(Document.class), eq("fs.files"));
    }
    
    @Test
    void updateFileDetails_MetadataDocIsNull() {
        Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));
        Document docWithNullMetadata = new Document(existingFileDoc);
        docWithNullMetadata.put("metadata", null); 
        docWithNullMetadata.put("filename", testFileId); 
        docWithNullMetadata.put("_id", new ObjectId()); 

        when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
                .thenReturn(docWithNullMetadata);

        Exception exception = assertThrows(StorageException.class, () -> {
            fileService.updateFileDetails(testUserId, testFileId, updateRequest);
        });
        assertEquals("File metadata is missing for fileId: " + testFileId, exception.getMessage());
        verify(mongoTemplate).findOne(eq(expectedQuery), eq(Document.class), eq("fs.files"));
    }


    @Test
    void updateFileDetails_UnauthorizedUser() {
        Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));
        Document docOtherOwner = new Document(existingFileDoc);
        Document metadataOtherOwner = new Document(docOtherOwner.get("metadata", Document.class));
        metadataOtherOwner.put("ownerId", "anotherUser");
        docOtherOwner.put("metadata", metadataOtherOwner);
        docOtherOwner.put("filename", testFileId);
        docOtherOwner.put("_id", new ObjectId());

        when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
                .thenReturn(docOtherOwner);

        assertThrows(UnauthorizedOperationException.class, () -> {
            fileService.updateFileDetails(testUserId, testFileId, updateRequest);
        });
        verify(mongoTemplate).findOne(eq(expectedQuery), eq(Document.class), eq("fs.files"));
    }

    @Test
    void updateFileDetails_NewFilenameSameAsOld() {
        // testFileId is the systemFilenameUUID. existingFileDoc has metadata.originalFilename = "old_user_filename.txt"
        Query findBySystemUUIDQuery = Query.query(Criteria.where("filename").is(testFileId));
        
        // Request to update with the same original filename it already has.
        FileUpdateRequest sameNameRequest = new FileUpdateRequest(metadataDoc.getString("originalFilename")); 
        
        when(mongoTemplate.findOne(eq(findBySystemUUIDQuery), eq(Document.class), eq("fs.files")))
                .thenReturn(existingFileDoc); // existingFileDoc already has this originalFilename

        FileResponse response = fileService.updateFileDetails(testUserId, testFileId, sameNameRequest);

        assertNotNull(response);
        assertEquals(testFileId, response.id()); // System UUID
        assertEquals(metadataDoc.getString("originalFilename"), response.filename()); // User's original filename
        
        verify(mongoTemplate).findOne(eq(findBySystemUUIDQuery), eq(Document.class), eq("fs.files")); // findOne is called
        verify(mongoTemplate, never()).exists(any(Query.class), eq("fs.files"));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq("fs.files"));
    }

    @Test
    void updateFileDetails_NewFilenameConflictsWithExisting() {
        Query findBySystemUUIDQuery = Query.query(Criteria.where("filename").is(testFileId));
        // updateRequest contains "new_user_filename.txt"

        when(mongoTemplate.findOne(eq(findBySystemUUIDQuery), eq(Document.class), eq("fs.files")))
                .thenReturn(existingFileDoc); // existingFileDoc has "old_user_filename.txt"
        
        // Simulate that "new_user_filename.txt" already exists for this user for a DIFFERENT system file
        Query conflictCheckQuery = Query.query(
            Criteria.where("metadata.originalFilename").is(updateRequest.newFilename())
                    .and("metadata.ownerId").is(testUserId)
                    .and("filename").ne(testFileId) // Different system UUID
        );
        when(mongoTemplate.exists(eq(conflictCheckQuery), eq("fs.files"))).thenReturn(true); 

        assertThrows(FileAlreadyExistsException.class, () -> {
            fileService.updateFileDetails(testUserId, testFileId, updateRequest);
        });
        verify(mongoTemplate).findOne(eq(findBySystemUUIDQuery), eq(Document.class), eq("fs.files"));
        verify(mongoTemplate).exists(eq(conflictCheckQuery), eq("fs.files"));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq("fs.files"));
    }

    @Test
    void updateFileDetails_UpdateOperationNoDocumentsModified() {
        Query findBySystemUUIDQuery = Query.query(Criteria.where("filename").is(testFileId));
        when(mongoTemplate.findOne(eq(findBySystemUUIDQuery), eq(Document.class), eq("fs.files")))
                .thenReturn(existingFileDoc) // Initial fetch
                .thenReturn(existingFileDoc); // Fetch after update (simulating no change occurred for some reason)

        Query conflictCheckQuery = Query.query(
            Criteria.where("metadata.originalFilename").is(updateRequest.newFilename())
                    .and("metadata.ownerId").is(testUserId)
                    .and("filename").ne(testFileId)
        );
        when(mongoTemplate.exists(eq(conflictCheckQuery), eq("fs.files"))).thenReturn(false); 

        UpdateResult mockUpdateResult = mock(UpdateResult.class);
        when(mockUpdateResult.getModifiedCount()).thenReturn(0L); 
        when(mongoTemplate.updateFirst(eq(findBySystemUUIDQuery), any(Update.class), eq("fs.files")))
                .thenReturn(mockUpdateResult);

        assertThrows(StorageException.class, () -> {
            fileService.updateFileDetails(testUserId, testFileId, updateRequest);
        });
        verify(mongoTemplate).updateFirst(eq(findBySystemUUIDQuery), any(Update.class), eq("fs.files"));
    }
    
    @Test
    void updateFileDetails_InvalidFileIdFormat() {
        // This test might be less relevant if fileId is now a generic string (UUID)
        // However, the service doesn't validate UUID format. If it did, this test would change.
        // For now, if findOne returns null for a non-UUID like string, it becomes ResourceNotFound.
        String invalidFileId = "not-a-uuid-at-all"; 
        Query queryForInvalidId = Query.query(Criteria.where("filename").is(invalidFileId));
        when(mongoTemplate.findOne(eq(queryForInvalidId), eq(Document.class), eq("fs.files"))).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> { // Now expects ResourceNotFound
            fileService.updateFileDetails(testUserId, invalidFileId, updateRequest);
        });
    }

    // Updated to include systemUUID and userOriginalFilename separately
    private Document createTestMongoDoc(ObjectId id, String systemUUID, String userOriginalFilename, Date uploadDate, String contentType, Long length, Document metadataProperties) {
        Document doc = new Document()
                .append("_id", id)
                .append("filename", systemUUID) // System UUID goes to top-level filename
                .append("uploadDate", uploadDate)
                .append("contentType", contentType);
        if (length != null) {
            doc.append("length", length);
        }
        
        Document fullMetadata = (metadataProperties != null) ? new Document(metadataProperties) : new Document();
        fullMetadata.put("originalFilename", userOriginalFilename); // User's filename goes into metadata
        // Ensure essential metadata like ownerId, visibility, token are in metadataProperties by the caller if needed for the specific test
        doc.append("metadata", fullMetadata);
        return doc;
    }

    @Test
    void listFiles_ByUser_Defaults() {
        String userId = "user123";
        String userOriginalFilename = "file1.txt"; 
        String systemUUID = UUID.randomUUID().toString(); 
        ObjectId docId = new ObjectId();
        String sortByApi = null; 
        String sortOrderApi = "asc";
        int pageNum = 0;
        int pageSize = 10;

        Document metadataForTestDoc = new Document("ownerId", userId)
                                        .append("visibility", Visibility.PRIVATE.name()) 
                                        .append("tags", List.of("t1"))
                                        .append("token", "tok1")
                                        .append("originalFilename", userOriginalFilename);
        Document testDoc = createTestMongoDoc(docId, systemUUID, userOriginalFilename, 
                                            new Date(), "text/plain", 100L, metadataForTestDoc);

        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(List.of(testDoc));
        when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(1L);

        Page<FileResponse> result = fileService.listFiles(userId, null, sortByApi, sortOrderApi, pageNum, pageSize);

        assertEquals(1L, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        FileResponse responseFile = result.getContent().get(0);
        assertEquals(systemUUID, responseFile.id());
        assertEquals(userOriginalFilename, responseFile.filename());

        ArgumentCaptor<Query> queryCaptorForFind = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptorForFind.capture(), eq(Document.class), eq("fs.files"));
        Query capturedFindQuery = queryCaptorForFind.getValue();

        Document queryObject = capturedFindQuery.getQueryObject();
        Document sortObject = capturedFindQuery.getSortObject();

        assertEquals(userId, queryObject.getString("metadata.ownerId"));
        assertNull(queryObject.get("metadata.tags"), "Tag filter should not be present");
        
        assertTrue(sortObject.containsKey("uploadDate"));
        assertEquals(1, sortObject.getInteger("uploadDate"));
        assertEquals(pageSize, capturedFindQuery.getLimit());
        assertEquals((long)pageNum * pageSize, capturedFindQuery.getSkip());
        
        ArgumentCaptor<Query> queryCaptorForCount = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(queryCaptorForCount.capture(), eq(Document.class), eq("fs.files"));
        Query capturedCountQuery = queryCaptorForCount.getValue();
        Document countQueryObject = capturedCountQuery.getQueryObject();

        assertEquals(userId, countQueryObject.getString("metadata.ownerId"));
        assertNull(countQueryObject.get("metadata.tags"), "Tag filter should not be present for count");
        
        assertTrue(capturedCountQuery.getSortObject().isEmpty(), "Sort should not be present for count query");
        assertEquals(0, capturedCountQuery.getLimit(), "Limit should not be applied for count query criteria");
        assertEquals(0, capturedCountQuery.getSkip(), "Skip should not be applied for count query criteria");
    }

    @Test
    void listFiles_Public_Defaults() {
        ObjectId docId = new ObjectId();
        String expectedUserFilename = "public_file.txt";
        String expectedSystemUUID = UUID.randomUUID().toString();
        Document metadataProps = new Document("ownerId", "anotherUser")
                                   .append("visibility", Visibility.PUBLIC.name())
                                   .append("token", "tok_public");
        Document testDoc = createTestMongoDoc(docId, expectedSystemUUID, expectedUserFilename, 
                                            new Date(), "image/png", 200L, metadataProps);
                                            
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(List.of(testDoc));
        when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(1L);

        Page<FileResponse> result = fileService.listFiles(null, null, "uploadDate", "asc", 0, 10);

        assertEquals(1L, result.getTotalElements());
        FileResponse responseFile = result.getContent().get(0);
        assertEquals(expectedSystemUUID, responseFile.id());
        assertEquals(expectedUserFilename, responseFile.filename());

        verify(mongoTemplate).find(argThat(q -> {
            Document queryObject = q.getQueryObject();
            Document sortObject = q.getSortObject();
            boolean publicVisMatch = Visibility.PUBLIC.name().equals(queryObject.get("metadata.visibility"));
            boolean noOwnerFilter = !queryObject.containsKey("metadata.ownerId"); // For public, service does not filter by owner
            boolean noTagFilter = !queryObject.containsKey("metadata.tags");
            boolean sortCorrect = sortObject.containsKey("uploadDate") && sortObject.getInteger("uploadDate") == 1;
            return publicVisMatch && noOwnerFilter && noTagFilter && sortCorrect;
        }), eq(Document.class), eq("fs.files"));
    }

    @Test
    void listFiles_ByUser_WithTag_SortDesc_Paginated() {
        String userId = "userXYZ";
        String filterTag = "projectX";
        String userOriginalFilename = "report.pdf"; 
        String systemUUID = UUID.randomUUID().toString(); 
        ObjectId docId = new ObjectId();
        String sortByApi = "filename";
        String sortOrderApi = "DeSc";
        int pageNum = 0;
        int pageSize = 5;

        Document metadataProps = new Document("ownerId", userId)
                                   .append("visibility", Visibility.PRIVATE.name()) 
                                   .append("tags", List.of(filterTag.toLowerCase(), "annual"))
                                   .append("token", "tok_report")
                                   .append("originalFilename", userOriginalFilename);
        Document testDoc = createTestMongoDoc(docId, systemUUID, userOriginalFilename, 
                                            new Date(), "application/pdf", 5000L, metadataProps);

        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(List.of(testDoc));
        when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(1L);

        Page<FileResponse> result = fileService.listFiles(userId, filterTag, sortByApi, sortOrderApi, pageNum, pageSize);

        assertEquals(1L, result.getTotalElements());
        FileResponse responseFile = result.getContent().get(0);
        assertEquals(systemUUID, responseFile.id());
        assertEquals(userOriginalFilename, responseFile.filename());

        ArgumentCaptor<Query> queryCaptorForFind = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptorForFind.capture(), eq(Document.class), eq("fs.files"));
        Query capturedFindQuery = queryCaptorForFind.getValue();
        
        Document queryObject = capturedFindQuery.getQueryObject();
        Document sortObject = capturedFindQuery.getSortObject();

        assertEquals(userId, queryObject.getString("metadata.ownerId"));
        assertEquals(filterTag.toLowerCase(), queryObject.getString("metadata.tags"));
            
        assertTrue(sortObject.containsKey("metadata.originalFilename"));
        assertEquals(-1, sortObject.getInteger("metadata.originalFilename"));
        assertEquals(pageSize, capturedFindQuery.getLimit());
        assertEquals((long)pageNum * pageSize, capturedFindQuery.getSkip());

        ArgumentCaptor<Query> queryCaptorForCount = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(queryCaptorForCount.capture(), eq(Document.class), eq("fs.files"));
        Query capturedCountQuery = queryCaptorForCount.getValue();
        Document countQueryObject = capturedCountQuery.getQueryObject();

        assertEquals(userId, countQueryObject.getString("metadata.ownerId"));
        assertEquals(filterTag.toLowerCase(), countQueryObject.getString("metadata.tags"));
            
        assertTrue(capturedCountQuery.getSortObject().isEmpty(), "Sort should not be present for count query");
        assertEquals(0, capturedCountQuery.getLimit(), "Limit should not be applied for count query criteria");
        assertEquals(0, capturedCountQuery.getSkip(), "Skip should not be applied for count query criteria");
    }

    @Test
    void listFiles_EmptyResult() {
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(Collections.emptyList());
        when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(0L);

        Page<FileResponse> result = fileService.listFiles("user1", "nonexistenttag", "size", "asc", 0, 10);
        assertTrue(result.isEmpty());
        assertEquals(0L, result.getTotalElements());
    }
    
    @Test
    void listFiles_InvalidSortByField_ThrowsException() {
        String userId = "testUser";
        String invalidSortField = "nonExistentField";

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            fileService.listFiles(userId, null, invalidSortField, "asc", 0, 10);
        });
        assertTrue(exception.getMessage().contains("Invalid sortBy field: " + invalidSortField));
    }

    @Test
    void listFiles_SortByUploadDate() {
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(Collections.emptyList());
        when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(0L);
        
        fileService.listFiles("user1", null, null, "asc", 0, 10);
        fileService.listFiles("user1", null, "uploaddate", "asc", 0, 10);
        
        verify(mongoTemplate, times(2)).find(argThat(q -> q.getSortObject().containsKey("uploadDate")), eq(Document.class), eq("fs.files"));
    }

    @Test
    void listFiles_SortByContentType() {
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(Collections.emptyList());
        when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(0L);
        fileService.listFiles("user1", null, "contenttype", "desc", 0, 10);
        verify(mongoTemplate).find(argThat(q -> q.getSortObject().containsKey("contentType") && q.getSortObject().getInteger("contentType") == -1), eq(Document.class), eq("fs.files"));
    }

    @Test
    void listFiles_SortByTag() {
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(Collections.emptyList());
        when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(0L);

        fileService.listFiles("user1", null, "tag", "asc", 0, 10);
        fileService.listFiles("user1", null, "tags", "asc", 0, 10);
        
        verify(mongoTemplate, times(2)).find(argThat(q -> q.getSortObject().containsKey("metadata.tags")), eq(Document.class), eq("fs.files"));
    }

    @Test
    void listFiles_DataMapping_AllVariations() {
        ObjectId id1_mongo = new ObjectId();
        String userFilename1 = "no_length.txt";
        String systemUUID1 = UUID.randomUUID().toString();
        Document meta1 = new Document("ownerId", "u1").append("visibility", "PUBLIC").append("token", "t1");
        Document docNoLength = createTestMongoDoc(id1_mongo, systemUUID1, userFilename1, new Date(), "text/plain", 0L, meta1);

        ObjectId id2_mongo = new ObjectId();
        String userFilename2 = "no_metadata.txt";
        String systemUUID2 = UUID.randomUUID().toString();
        Document docNoMetadata = createTestMongoDoc(id2_mongo, systemUUID2, userFilename2, new Date(), "text/plain", 100L, null);

        ObjectId id3_mongo = new ObjectId();
        String userFilename3 = "null_vis_str.txt";
        String systemUUID3 = UUID.randomUUID().toString();
        Document meta3 = new Document("ownerId", "u3").append("visibility", null).append("token", "t3");
        Document docNullVisibilityStr = createTestMongoDoc(id3_mongo, systemUUID3, userFilename3, new Date(), "text/plain", 100L, meta3);

        ObjectId id4_mongo = new ObjectId();
        String userFilename4 = "invalid_vis_str.txt";
        String systemUUID4 = UUID.randomUUID().toString();
        Document meta4 = new Document("ownerId", "u4").append("visibility", "WRONG_VISIBILITY").append("token", "t4");
        Document docInvalidVisibilityStr = createTestMongoDoc(id4_mongo, systemUUID4, userFilename4, new Date(), "text/plain", 100L, meta4);

        ObjectId id5_mongo = new ObjectId();
        String userFilename5 = "null_token.txt";
        String systemUUID5 = UUID.randomUUID().toString();
        Document meta5 = new Document("ownerId", "u5").append("visibility", "PRIVATE").append("token", null);
        Document docNullToken = createTestMongoDoc(id5_mongo, systemUUID5, userFilename5, new Date(), "text/plain", 100L, meta5);
        
        ObjectId id6_mongo = new ObjectId();
        String userFilename6 = "missing_tags.txt";
        String systemUUID6 = UUID.randomUUID().toString();
        Document meta6 = new Document("ownerId", "u6").append("visibility", "PUBLIC").append("token", "t6");
        Document docMissingTags = createTestMongoDoc(id6_mongo, systemUUID6, userFilename6, new Date(), "text/plain", 100L, meta6);

        String userFilename7 = "null_system_id_file.txt";
        Document docNullSystemUUID = new Document()
                .append("_id", new ObjectId()) 
                .append("filename", null) // System UUID (FileResponse.id) is null
                .append("uploadDate", new Date())
                .append("contentType", "text/plain")
                .append("length", 123L)
                .append("metadata", new Document("ownerId", "u7").append("visibility", "PUBLIC").append("token", "t7").append("originalFilename", userFilename7));

        List<Document> allDocs = List.of(docNoLength, docNoMetadata, docNullVisibilityStr, docInvalidVisibilityStr, docNullToken, docMissingTags, docNullSystemUUID);
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn(allDocs);
        when(mongoTemplate.count(any(Query.class), eq(Document.class), eq("fs.files"))).thenReturn((long) allDocs.size());

        Page<FileResponse> results = fileService.listFiles(null, null, null, "asc", 0, 10);
        assertEquals(allDocs.size(), results.getContent().size());

        FileResponse resNoLength = results.getContent().stream().filter(r -> systemUUID1.equals(r.id())).findFirst().orElseThrow();
        assertEquals(0L, resNoLength.size()); 
        assertEquals(userFilename1, resNoLength.filename());

        FileResponse resNoMetadata = results.getContent().stream().filter(r -> systemUUID2.equals(r.id())).findFirst().orElseThrow();
        assertEquals(Visibility.PRIVATE, resNoMetadata.visibility()); 
        assertTrue(resNoMetadata.tags().isEmpty()); 
        assertNull(resNoMetadata.downloadLink()); 
        assertEquals(userFilename2, resNoMetadata.filename());

        FileResponse resNullVisStr = results.getContent().stream().filter(r -> systemUUID3.equals(r.id())).findFirst().orElseThrow();
        assertEquals(Visibility.PRIVATE, resNullVisStr.visibility()); 
        assertEquals(userFilename3, resNullVisStr.filename());

        FileResponse resInvalidVisStr = results.getContent().stream().filter(r -> systemUUID4.equals(r.id())).findFirst().orElseThrow();
        assertEquals(Visibility.PRIVATE, resInvalidVisStr.visibility()); 
        assertEquals(userFilename4, resInvalidVisStr.filename());

        FileResponse resNullToken = results.getContent().stream().filter(r -> systemUUID5.equals(r.id())).findFirst().orElseThrow();
        assertNull(resNullToken.downloadLink()); 
        assertEquals(userFilename5, resNullToken.filename());

        FileResponse resMissingTags = results.getContent().stream().filter(r -> systemUUID6.equals(r.id())).findFirst().orElseThrow();
        assertTrue(resMissingTags.tags().isEmpty()); 
        assertEquals(userFilename6, resMissingTags.filename());

        FileResponse resNullSystemUUID = results.getContent().stream().filter(r -> userFilename7.equals(r.filename())).findFirst().orElseThrow();
        assertNull(resNullSystemUUID.id()); 
    }

    @Test
    void deleteFile_Success() {
        // testFileId is the systemFilenameUUID from setUp
        // existingFileDoc (from setUp) has its top-level "filename" field set to testFileId 
        // and metadata.ownerId set to testUserId.
        Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));

        when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
            .thenReturn(existingFileDoc); 
        doNothing().when(gridFsTemplate).delete(eq(expectedQuery));

        assertDoesNotThrow(() -> {
            fileService.deleteFile(testUserId, testFileId);
        });

        verify(mongoTemplate).findOne(eq(expectedQuery), eq(Document.class), eq("fs.files"));
        verify(gridFsTemplate).delete(eq(expectedQuery));
    }

    @Test
    void deleteFile_NotFound() {
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("fs.files")))
            .thenReturn(null);

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> {
            fileService.deleteFile(testUserId, testFileId);
        });
        assertTrue(ex.getMessage().contains("File not found with id: " + testFileId));
        verify(gridFsTemplate, never()).delete(any(Query.class));
    }

    @Test
    void deleteFile_UnauthorizedUser() {
        // Prepare a document that will be found by testFileId (system UUID)
        // but has a different ownerId.
        Document metadataOtherOwner = new Document(metadataDoc); // Copy from setUp
        metadataOtherOwner.put("ownerId", "anotherUser123");

        Document fileDocOtherOwner = new Document(existingFileDoc); // Copy from setUp
        fileDocOtherOwner.put("metadata", metadataOtherOwner);
        // Ensure filename is testFileId (system UUID) for the query to find it.
        // existingFileDoc already has filename = testFileId.

        Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));
        when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
            .thenReturn(fileDocOtherOwner);

        UnauthorizedOperationException ex = assertThrows(UnauthorizedOperationException.class, () -> {
            fileService.deleteFile(testUserId, testFileId); 
        });
        assertTrue(ex.getMessage().contains("not authorized to delete fileId"));
        verify(mongoTemplate).findOne(eq(expectedQuery), eq(Document.class), eq("fs.files"));
        verify(gridFsTemplate, never()).delete(any(Query.class));
    }

    @Test
    void deleteFile_MetadataMissingOnDocument_ShouldThrowUnauthorizedOrNPE() {
        // Ensure the test document is findable by its system UUID (testFileId)
        Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));
        Document fileDocWithoutMetadata = new Document()
            .append("_id", new ObjectId()) // Has a DB _id
            .append("filename", testFileId); // System UUID
        // metadata field is completely missing

        when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
            .thenReturn(fileDocWithoutMetadata);

        UnauthorizedOperationException ex = assertThrows(UnauthorizedOperationException.class, () -> {
            fileService.deleteFile(testUserId, testFileId);
        });
        // Service will attempt to get metadata, it will be null, ownerId will be null.
        // The check !userId.equals(ownerId) will become !"user123".equals(null), which is true.
        String expectedMessagePart = "User '" + testUserId + "' not authorized to delete fileId: " + testFileId;
        assertTrue(ex.getMessage().contains(expectedMessagePart));
        verify(gridFsTemplate, never()).delete(any(Query.class));
    }
    
    @Test
    void deleteFile_MetadataFieldExistsButIsNull_ShouldThrowUnauthorized() {
        Query expectedQuery = Query.query(Criteria.where("filename").is(testFileId));
        Document fileDocWithNullMetadata = new Document()
            .append("_id", new ObjectId())
            .append("filename", testFileId) // System UUID
            .append("metadata", null); // Metadata field exists but is null

        when(mongoTemplate.findOne(eq(expectedQuery), eq(Document.class), eq("fs.files")))
            .thenReturn(fileDocWithNullMetadata);

        UnauthorizedOperationException ex = assertThrows(UnauthorizedOperationException.class, () -> {
            fileService.deleteFile(testUserId, testFileId);
        });
        String expectedMessagePart = "User '" + testUserId + "' not authorized to delete fileId: " + testFileId;
        assertTrue(ex.getMessage().contains(expectedMessagePart));
        verify(gridFsTemplate, never()).delete(any(Query.class));
    }

    @Test
    void deleteFile_InvalidFileIdFormat() {
        String invalidFileId = "not-a-valid-uuid-or-any-other-format-that-would-match"; 
        Query queryForInvalidId = Query.query(Criteria.where("filename").is(invalidFileId));

        // If an invalid fileId (that doesn't match any filename/UUID) is passed,
        // findOne should return null, leading to ResourceNotFoundException.
        when(mongoTemplate.findOne(eq(queryForInvalidId), eq(Document.class), eq("fs.files")))
            .thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> { // Changed from IllegalArgumentException
            fileService.deleteFile(testUserId, invalidFileId);
        });

        verify(mongoTemplate).findOne(eq(queryForInvalidId), eq(Document.class), eq("fs.files"));
        verify(gridFsTemplate, never()).delete(any(Query.class));
    }

} 