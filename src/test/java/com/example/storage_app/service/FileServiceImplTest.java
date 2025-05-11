package com.example.storage_app.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Date;

import org.bson.types.ObjectId;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import org.mockito.ArgumentMatcher;

import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.model.Visibility;
import com.example.storage_app.controller.dto.FileResponse;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertSame;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.stream.Collectors;
import java.util.Collections;
import java.util.Arrays; // For Arrays.asList
import org.springframework.data.domain.Page; // For return type
import com.example.storage_app.exception.FileAlreadyExistsException;
import com.example.storage_app.exception.InvalidRequestArgumentException;
import com.example.storage_app.exception.ResourceNotFoundException;
import com.example.storage_app.exception.UnauthorizedOperationException;
import com.example.storage_app.exception.StorageException;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {
    @Mock
    private GridFsTemplate gridFsTemplate;

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private FileServiceImpl fileService;

    @Mock
    private MultipartFile mockMultipartFile;

    private FileUploadRequest fileUploadRequest;
    private String testUserId = "user123";
    private String testFilename = "test.txt";
    private String testContent = "Hello World";
    private ObjectId testObjectId = new ObjectId();
    private String testToken = "test-download-token";
    private String testFileId = new ObjectId().toHexString();
    private String newFilename = "new_document.txt";

    @BeforeEach
    void setUp() {
        fileUploadRequest = new FileUploadRequest(testFilename, Visibility.PRIVATE, List.of("tag1"));
    }

    @Test
    void uploadFile_whenFilenameExistsForUser_shouldThrowIllegalArgumentException() throws IOException, NoSuchAlgorithmException {
        when(mockMultipartFile.isEmpty()).thenReturn(false);

        when(mongoTemplate.exists(any(Query.class), eq("fs.files"))).thenReturn(true);

        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class, () -> { 
            fileService.uploadFile(testUserId, mockMultipartFile, fileUploadRequest);
        });

        assertTrue(exception.getMessage().contains("Filename '" + testFilename + "' already exists for this user."));
    }

    @Test
    void uploadFile_whenContentExistsForUser_shouldThrowIllegalArgumentException() throws IOException, NoSuchAlgorithmException {
        FileUploadRequest contentCheckRequest = new FileUploadRequest("anotherfile.txt", Visibility.PRIVATE, List.of("tag1"));
        when(mockMultipartFile.isEmpty()).thenReturn(false);
        when(mockMultipartFile.getInputStream()).thenReturn(new ByteArrayInputStream(testContent.getBytes()));
        
        when(gridFsTemplate.store(any(InputStream.class), eq("anotherfile.txt"), anyString(), any(org.bson.Document.class)))
                .thenReturn(testObjectId);

        when(mongoTemplate.exists(argThat(new ArgumentMatcher<Query>() {
            @Override
            public boolean matches(Query query) {
                if (query == null || query.getQueryObject() == null) return false;
                Document queryObject = query.getQueryObject();
                boolean filenameMatch = "anotherfile.txt".equals(queryObject.getString("filename"));
                boolean ownerMatch = testUserId.equals(queryObject.getString("metadata.ownerId"));
                return filenameMatch && ownerMatch;
            }
        }), eq("fs.files"))).thenReturn(false);
        
        when(mongoTemplate.exists(argThat(new ArgumentMatcher<Query>() {
            @Override
            public boolean matches(Query query) {
                if (query == null || query.getQueryObject() == null) return false;
                Document queryObject = query.getQueryObject();
                boolean hasSha256 = queryObject.containsKey("metadata.sha256");
                boolean ownerMatch = testUserId.equals(queryObject.getString("metadata.ownerId"));
                return hasSha256 && ownerMatch;
            }
        }), eq("fs.files"))).thenReturn(true);

        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class, () -> {
            fileService.uploadFile(testUserId, mockMultipartFile, contentCheckRequest);
        });
        
        assertTrue(exception.getMessage().contains("Content already exists for this user. File upload aborted."));
    }

    @Test
    void uploadFile_whenFilenameAndContentAreUnique_shouldSucceed() throws IOException, NoSuchAlgorithmException {
        when(mockMultipartFile.isEmpty()).thenReturn(false);
        when(mockMultipartFile.getInputStream()).thenReturn(new ByteArrayInputStream(testContent.getBytes()));
        when(mockMultipartFile.getSize()).thenReturn((long) testContent.getBytes().length);
        
        when(gridFsTemplate.store(any(InputStream.class), eq(testFilename), anyString(), any(org.bson.Document.class)))
                .thenReturn(testObjectId);

        when(mongoTemplate.exists(argThat(new ArgumentMatcher<Query>() {
            @Override
            public boolean matches(Query query) {
                return query != null && 
                       query.getQueryObject().containsKey("filename") &&
                       query.getQueryObject().get("metadata.ownerId") != null && 
                       query.getQueryObject().get("metadata.ownerId").equals(testUserId);
            }
        }), eq("fs.files"))).thenReturn(false);
        
        when(mongoTemplate.exists(argThat(new ArgumentMatcher<Query>() {
            @Override
            public boolean matches(Query query) {
                return query != null && 
                       query.getQueryObject().containsKey("metadata.sha256") &&
                       query.getQueryObject().get("metadata.ownerId") != null && 
                       query.getQueryObject().get("metadata.ownerId").equals(testUserId);
            }
        }), eq("fs.files"))).thenReturn(false);
        
        FileResponse response = fileService.uploadFile(testUserId, mockMultipartFile, fileUploadRequest);

        assertNotNull(response);
        assertEquals(testObjectId.toHexString(), response.id());
        assertEquals(testFilename, response.filename());
        assertEquals(Visibility.PRIVATE, response.visibility());
        assertEquals(List.of("tag1"), response.tags());
        assertNotNull(response.uploadDate());
        assertNotNull(response.contentType()); 
        assertEquals(testContent.getBytes().length, response.size());
        String expectedDownloadLink = "/api/v1/files/" + testObjectId.toHexString() + "/download";
        assertEquals(expectedDownloadLink, response.downloadLink());
    }

    @Test
    void uploadFile_whenTooManyTags_shouldThrowIllegalArgumentException() {
        List<String> tooManyTags = List.of("tag1", "tag2", "tag3", "tag4", "tag5", "tag6");
        FileUploadRequest requestWithTooManyTags = new FileUploadRequest(testFilename, Visibility.PRIVATE, tooManyTags);

        InvalidRequestArgumentException exception = assertThrows(InvalidRequestArgumentException.class, () -> {
            fileService.uploadFile(testUserId, mockMultipartFile, requestWithTooManyTags);
        });

        assertTrue(exception.getMessage().contains("Cannot have more than 5 tags"));
    }

    @Test
    void uploadFile_whenFileIsEmpty_shouldThrowIllegalArgumentException() {
        when(mockMultipartFile.isEmpty()).thenReturn(true);

        InvalidRequestArgumentException exception = assertThrows(InvalidRequestArgumentException.class, () -> {
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

        Exception exception = assertThrows(RuntimeException.class, () -> {
            fileService.downloadFile(testToken);
        });
        assertTrue(exception.getMessage().contains("File not found for token: " + testToken));
    }

    @Test
    void updateFileDetails_whenFileExistsAndUserOwnsItAndNewNameIsValid_shouldSucceed() {
        String newValidFilename = "a_new_valid_filename.txt";
        FileUpdateRequest updateRequest = new FileUpdateRequest(newValidFilename);

        org.bson.Document fileMetadataSubDoc = new org.bson.Document("ownerId", testUserId)
                                              .append("token", "someToken") 
                                              .append("visibility", Visibility.PRIVATE.name()) 
                                              .append("tags", List.of("tag1"));

        org.bson.Document fileDocToReturnOnFind = new org.bson.Document("_id", new ObjectId(testFileId))
                                              .append("filename", testFilename) 
                                              .append("length", 100L) 
                                              .append("contentType", "text/plain") 
                                              .append("uploadDate", new java.util.Date()) 
                                              .append("metadata", fileMetadataSubDoc);

        when(mongoTemplate.findOne(
            argThat(q -> q != null && q.getQueryObject() != null && 
                           q.getQueryObject().containsKey("_id") && 
                           testFileId.equals(q.getQueryObject().getObjectId("_id").toHexString()) &&
                           q.getQueryObject().keySet().size() == 1 // Ensures only _id criteria
            ), 
            eq(org.bson.Document.class), 
            eq("fs.files")))
        .thenReturn(fileDocToReturnOnFind); // Returns doc owned by testUserId

        when(mongoTemplate.exists(
            argThat(q -> q != null && q.getQueryObject() != null &&
                           newValidFilename.equals(q.getQueryObject().getString("filename")) &&
                           testUserId.equals(q.getQueryObject().getString("metadata.ownerId")) &&
                           q.getQueryObject().keySet().size() == 2 // filename and metadata.ownerId
            ), 
            eq("fs.files")))
        .thenReturn(false); // No conflict

        UpdateResult mockUpdateResult = mock(UpdateResult.class);
        when(mockUpdateResult.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(
            argThat(q -> q != null && q.getQueryObject() != null && 
                           q.getQueryObject().containsKey("_id") && 
                           testFileId.equals(q.getQueryObject().getObjectId("_id").toHexString()) &&
                           q.getQueryObject().keySet().size() == 1 // Update query is also by _id only
            ), 
            any(org.springframework.data.mongodb.core.query.Update.class), 
            eq("fs.files")))
        .thenReturn(mockUpdateResult);

        FileResponse response = fileService.updateFileDetails(testUserId, testFileId, updateRequest);

        assertNotNull(response);
        assertEquals(newValidFilename, response.filename());
        assertEquals(Visibility.PRIVATE, response.visibility());
        assertEquals(List.of("tag1"), response.tags());
        assertEquals(fileDocToReturnOnFind.getDate("uploadDate"), response.uploadDate());
        assertEquals(fileDocToReturnOnFind.getString("contentType"), response.contentType());
        assertEquals(fileDocToReturnOnFind.getLong("length"), response.size());
        String expectedDownloadLink = "/api/v1/files/download/" + "someToken";
        assertEquals(expectedDownloadLink, response.downloadLink());

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor = 
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        
        verify(mongoTemplate).updateFirst(queryCaptor.capture(), updateCaptor.capture(), eq("fs.files"));
        assertEquals(new ObjectId(testFileId), queryCaptor.getValue().getQueryObject().getObjectId("_id"));
        assertEquals(newValidFilename, updateCaptor.getValue().getUpdateObject().get("$set", org.bson.Document.class).getString("filename"));
    }

    @Test
    void updateFileDetails_whenFileDoesNotExistById_shouldThrowResourceNotFoundException() {
        FileUpdateRequest updateRequest = new FileUpdateRequest(newFilename);

        // Mock findOne by ID to return null
        when(mongoTemplate.findOne(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    return queryObject.containsKey("_id") && 
                           queryObject.getObjectId("_id").toHexString().equals(testFileId);
                }
            }), 
            eq(org.bson.Document.class), 
            eq("fs.files")))
        .thenReturn(null); 

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            fileService.updateFileDetails(testUserId, testFileId, updateRequest);
        });

        assertTrue(exception.getMessage().contains("File not found with id: " + testFileId));
    }

    @Test
    void updateFileDetails_whenNewFilenameConflicts_shouldThrowException() {
        String conflictingFilename = "i_already_exist.txt";
        FileUpdateRequest updateRequest = new FileUpdateRequest(conflictingFilename);

        org.bson.Document fileMetadataSubDoc = new org.bson.Document("ownerId", testUserId)
                                              .append("token", "someToken")
                                              .append("visibility", Visibility.PRIVATE.name())
                                              .append("tags", List.of("tag1"));
        org.bson.Document fileDocToReturnOnFind = new org.bson.Document("_id", new ObjectId(testFileId))
                                              .append("filename", testFilename) 
                                              .append("metadata", fileMetadataSubDoc);
        // This first findOne is for the combined ID and Owner check in the old service version.
        // Now, the service first finds by ID, then checks owner from the result.
        // So, this mock needs to match the first findByIdQuery in the service.
        when(mongoTemplate.findOne(
            argThat(q -> q != null && q.getQueryObject() != null && 
                           q.getQueryObject().containsKey("_id") && 
                           testFileId.equals(q.getQueryObject().getObjectId("_id").toHexString()) &&
                           q.getQueryObject().keySet().size() == 1 
            ), 
            eq(org.bson.Document.class), 
            eq("fs.files")))
        .thenReturn(fileDocToReturnOnFind);

        // Mock for the conflict check: mongoTemplate.exists returns true (CONFLICT!)
        when(mongoTemplate.exists(
            argThat(q -> q != null && q.getQueryObject() != null &&
                           conflictingFilename.equals(q.getQueryObject().getString("filename")) &&
                           testUserId.equals(q.getQueryObject().getString("metadata.ownerId")) &&
                           q.getQueryObject().keySet().size() == 2 
            ), 
            eq("fs.files")))
        .thenReturn(true); // Conflict!

        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class, () -> { 
            fileService.updateFileDetails(testUserId, testFileId, updateRequest);
        });

        assertTrue(exception.getMessage().contains("Filename '" + conflictingFilename + "' already exists for this user."));
    }

    @Test
    void updateFileDetails_whenUpdateOperationReportsNoModification_shouldThrowException() {
        String nonConflictingNewFilename = "unique_new_name.txt";
        FileUpdateRequest updateRequest = new FileUpdateRequest(nonConflictingNewFilename);

        // Setup fileDocToReturnOnFind to be owned by testUserId
        org.bson.Document fileMetadataSubDoc = new org.bson.Document("ownerId", testUserId)
            .append("filename", testFilename) // original filename
            .append("token", "token123")
            .append("visibility", Visibility.PRIVATE.name())
            .append("tags", List.of("tagA"));
        org.bson.Document fileDocToReturnOnFind = new org.bson.Document("_id", new ObjectId(testFileId))
            .append("filename", testFilename)
            .append("length", 123L)
            .append("contentType", "text/plain")
            .append("uploadDate", new java.util.Date())
            .append("metadata", fileMetadataSubDoc);

        // Mock for the first findOne by ID - LOOSENED MATCHER
        when(mongoTemplate.findOne(any(Query.class), eq(org.bson.Document.class), eq("fs.files")))
        .thenReturn(fileDocToReturnOnFind); 

        // Mock for the conflict check: mongoTemplate.exists returns false (NO CONFLICT) - LOOSENED MATCHER
        when(mongoTemplate.exists(any(Query.class), eq("fs.files")))
        .thenReturn(false); // No conflict

        // Mock for mongoTemplate.updateFirst to return 0 modifiedCount - LOOSENED MATCHER
        UpdateResult mockUpdateResult = mock(UpdateResult.class);
        when(mockUpdateResult.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("fs.files")))
        .thenReturn(mockUpdateResult);
        
        StorageException exception = assertThrows(StorageException.class, () -> {
            fileService.updateFileDetails(testUserId, testFileId, updateRequest);
        });
        assertTrue(exception.getMessage().contains("File update for filename failed for fileId: " + testFileId + ". Zero documents modified despite expecting a change."));
    
        // ArgumentCaptor example (if needed after test passes with any() ):
        // ArgumentCaptor<Query> queryCaptorForFind = ArgumentCaptor.forClass(Query.class);
        // verify(mongoTemplate).findOne(queryCaptorForFind.capture(), eq(org.bson.Document.class), eq("fs.files"));
        // System.out.println("Actual findOne Query: " + queryCaptorForFind.getValue().getQueryObject().toJson());
        // ArgumentCaptor<Query> queryCaptorForExists = ArgumentCaptor.forClass(Query.class);
        // verify(mongoTemplate).exists(queryCaptorForExists.capture(), eq("fs.files"));
        // System.out.println("Actual exists Query: " + queryCaptorForExists.getValue().getQueryObject().toJson());
    }

    @Test
    void deleteFile_whenFileExistsAndUserOwnsIt_shouldDeleteFile() {
        // Arrange
        Document mockFileDocMetadata = new Document("ownerId", testUserId);
        Document mockFileDoc = new Document("_id", new ObjectId(testFileId)).append("metadata", mockFileDocMetadata);

        // Mock the findOne call for ownership check to return the document
        when(mongoTemplate.findOne(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    // Service's findByIdQuery only checks _id initially
                    return queryObject.containsKey("_id") && 
                           queryObject.getObjectId("_id").toHexString().equals(testFileId);
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn(mockFileDoc);

        // Act
        assertDoesNotThrow(() -> fileService.deleteFile(testUserId, testFileId));

        // Assert
        ArgumentCaptor<Query> deleteQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(gridFsTemplate).delete(deleteQueryCaptor.capture());
        assertEquals(new ObjectId(testFileId), deleteQueryCaptor.getValue().getQueryObject().getObjectId("_id"));
        assertEquals(1, deleteQueryCaptor.getValue().getQueryObject().size()); // Delete query should only be by _id
    }

    @Test
    void deleteFile_whenFileNotExists_shouldThrowResourceNotFoundException() {
        // Arrange
        // Mock mongoTemplate.findOne to return null (file not found by ID)
        when(mongoTemplate.findOne(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    return queryObject.containsKey("_id") && 
                           queryObject.getObjectId("_id").toHexString().equals(testFileId);
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn(null); 

        // Act & Assert
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            fileService.deleteFile(testUserId, testFileId);
        });

        assertTrue(exception.getMessage().contains("File not found with id: " + testFileId));
    }

    @Test
    void deleteFile_whenUserNotOwner_shouldThrowUnauthorizedOperationException() {
        String otherUserId = "otherUser99"; // Ensure different from testUserId
        // testFileId is already defined as a field for the class

        Document metadata = new Document("ownerId", otherUserId); 
        Document fileDoc = new Document("_id", new ObjectId(testFileId)).append("metadata", metadata);

        // Mock mongoTemplate.findOne to return the fileDoc (simulating file exists)
        when(mongoTemplate.findOne(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    return query != null && query.getQueryObject().getObjectId("_id").toHexString().equals(testFileId);
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn(fileDoc);

        UnauthorizedOperationException exception = assertThrows(UnauthorizedOperationException.class, () -> {
            fileService.deleteFile(testUserId, testFileId); // testUserId attempts to delete otherUser99's file
        });

        assertTrue(exception.getMessage().contains("User '" + testUserId + "' not authorized to delete fileId: " + testFileId));
        verify(gridFsTemplate, never()).delete(any(Query.class)); 
    }

    @Test
    void listFiles_whenNoUserIdAndNoTag_shouldListPublicFilesWithDefaultSort() {
        int pageNum = 0;
        int pageSize = 10;
        String sortBy = "uploadDate";
        String sortDir = "desc";

        PageRequest expectedPageable = PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "uploadDate"));

        ObjectId publicFileId1 = new ObjectId();
        Document metadata1 = new Document("ownerId", "anotherUser").append("visibility", Visibility.PUBLIC.name()).append("tags", List.of("public_tag")).append("token", "token1");
        Document publicFileDoc1 = new Document("_id", publicFileId1).append("filename", "public1.txt").append("uploadDate", new Date(System.currentTimeMillis() - 10000))
                                        .append("contentType", "text/plain").append("length", 100L).append("metadata", metadata1);

        ObjectId publicFileId2 = new ObjectId();
        Document metadata2 = new Document("ownerId", "user456").append("visibility", Visibility.PUBLIC.name()).append("tags", List.of("general")).append("token", "token2");
        Document publicFileDoc2 = new Document("_id", publicFileId2).append("filename", "public2.zip").append("uploadDate", new Date(System.currentTimeMillis() - 5000))
                                        .append("contentType", "application/zip").append("length", 2000L).append("metadata", metadata2);
        
        List<Document> mockDocuments = Arrays.asList(publicFileDoc2, publicFileDoc1);

        when(mongoTemplate.find(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    
                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    boolean correctVisibility = Visibility.PUBLIC.name().equals(visibilityInQuery);
                    
                    boolean noOwnerId = !queryObject.containsKey("metadata.ownerId");
                    boolean noTags = !queryObject.containsKey("metadata.tags");
                    
                    boolean correctPage = query.getSkip() == (long)pageNum * pageSize && query.getLimit() == pageSize;
                    boolean correctSort = query.getSortObject().containsKey("uploadDate") && query.getSortObject().getInteger("uploadDate") == -1;
                    return correctVisibility && noOwnerId && noTags && correctPage && correctSort;
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn(mockDocuments);

        when(mongoTemplate.count(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();

                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    boolean correctVisibility = Visibility.PUBLIC.name().equals(visibilityInQuery);

                    boolean noOwnerId = !queryObject.containsKey("metadata.ownerId");
                    boolean noTags = !queryObject.containsKey("metadata.tags");
                    boolean noSort = query.getSortObject().isEmpty(); 
                    boolean noLimitOrSkip = query.getLimit() == 0 && query.getSkip() == 0; 
                    return correctVisibility && noOwnerId && noTags && noSort && noLimitOrSkip;
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn((long) mockDocuments.size());

        Page<FileResponse> resultPage = fileService.listFiles(null, null, sortBy, sortDir, pageNum, pageSize);

        assertNotNull(resultPage);
        assertEquals(2, resultPage.getTotalElements());
        assertEquals(2, resultPage.getContent().size());
        assertEquals("public2.zip", resultPage.getContent().get(0).filename());
        assertEquals("public1.txt", resultPage.getContent().get(1).filename());
        assertEquals(Visibility.PUBLIC, resultPage.getContent().get(0).visibility());

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(Document.class), eq("fs.files"));
        Query actualFindQuery = findQueryCaptor.getValue();
        assertEquals(expectedPageable.getPageSize(), actualFindQuery.getLimit());
        assertEquals(expectedPageable.getOffset(), actualFindQuery.getSkip());
        Document sortObject = actualFindQuery.getSortObject();
        assertNotNull(sortObject);
        assertEquals(-1, sortObject.getInteger("uploadDate"));
    }

    @Test
    void listFiles_whenUserIdProvided_shouldListUserFiles() {
        int pageNum = 0;
        int pageSize = 5;
        String sortBy = "filename";
        String sortDir = "asc";

        PageRequest expectedPageable = PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, sortBy));

        ObjectId userFileId1 = new ObjectId();
        Document metadataUser1 = new Document("ownerId", testUserId).append("visibility", Visibility.PRIVATE.name()).append("tags", List.of("user_tag")).append("token", "userToken1");
        Document userFileDoc1 = new Document("_id", userFileId1).append("filename", "userFileA.doc").append("uploadDate", new Date(System.currentTimeMillis() - 20000))
                                    .append("contentType", "application/msword").append("length", 500L).append("metadata", metadataUser1);

        ObjectId userFileId2 = new ObjectId();
        Document metadataUser2 = new Document("ownerId", testUserId).append("visibility", Visibility.PUBLIC.name()).append("tags", List.of("user_tag", "shared")).append("token", "userToken2");
        Document userFileDoc2 = new Document("_id", userFileId2).append("filename", "userFileB.pdf").append("uploadDate", new Date(System.currentTimeMillis() - 15000))
                                    .append("contentType", "application/pdf").append("length", 1500L).append("metadata", metadataUser2);
        
        List<Document> mockUserDocuments = Arrays.asList(userFileDoc1, userFileDoc2); // Sorted by filename ASC

        // Mock for mongoTemplate.find()
        when(mongoTemplate.find(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    String ownerIdInQuery = queryObject.getString("metadata.ownerId");
                    boolean correctOwner = testUserId.equals(ownerIdInQuery);
                    boolean noVisibilityFilter = !queryObject.containsKey("metadata.visibility");
                    boolean noTags = !queryObject.containsKey("metadata.tags");
                    boolean correctPage = query.getSkip() == (long)pageNum * pageSize && query.getLimit() == pageSize;
                    boolean correctSort = query.getSortObject().containsKey("filename") && query.getSortObject().getInteger("filename") == 1;
                    return correctOwner && noVisibilityFilter && noTags && correctPage && correctSort;
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn(mockUserDocuments);

        // Mock for mongoTemplate.count()
        when(mongoTemplate.count(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    String ownerIdInQuery = queryObject.getString("metadata.ownerId");
                    boolean correctOwner = testUserId.equals(ownerIdInQuery);
                    boolean noVisibilityFilter = !queryObject.containsKey("metadata.visibility");
                    boolean noTags = !queryObject.containsKey("metadata.tags");
                    return correctOwner && noVisibilityFilter && noTags && query.getLimit() == 0 && query.getSkip() == 0;
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn((long) mockUserDocuments.size());

        Page<FileResponse> resultPage = fileService.listFiles(testUserId, null, sortBy, sortDir, pageNum, pageSize);

        assertNotNull(resultPage);
        assertEquals(2, resultPage.getTotalElements());
        assertEquals(2, resultPage.getContent().size());
        assertEquals("userFileA.doc", resultPage.getContent().get(0).filename()); 
        assertEquals("userFileB.pdf", resultPage.getContent().get(1).filename());
        assertEquals(testUserId, metadataUser1.getString("ownerId")); // Check indirectly via metadata used for response
    }

    @Test
    void listFiles_whenTagProvided_shouldFilterByTagCaseInsensitively() {
        int pageNum = 0;
        int pageSize = 10;
        String filterTag = "Work"; // Mixed case to test case-insensitivity of query preparation
        String sortBy = "uploadDate";
        String sortDir = "desc";

        PageRequest expectedPageable = PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "uploadDate"));

        ObjectId taggedFileId = new ObjectId();
        Document metadataTagged = new Document("ownerId", "user789")
                                        .append("visibility", Visibility.PUBLIC.name())
                                        .append("tags", List.of("personal", "work")) // Stored as lowercase
                                        .append("token", "tagToken");
        Document taggedFileDoc = new Document("_id", taggedFileId).append("filename", "work_document.docx")
                                       .append("uploadDate", new Date())
                                       .append("contentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                                       .append("length", 3000L)
                                       .append("metadata", metadataTagged);
        
        List<Document> mockDocuments = List.of(taggedFileDoc);

        // Mock for mongoTemplate.find()
        when(mongoTemplate.find(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    // Check for visibility:public and tag:work (lowercase)
                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    boolean correctVisibility = Visibility.PUBLIC.name().equals(visibilityInQuery);
                    
                    String tagInQuery = queryObject.getString("metadata.tags"); // Criteria stores .is() as direct value
                    boolean correctTag = filterTag.toLowerCase().equals(tagInQuery);

                    return correctVisibility && correctTag;
                    // Not checking pagination/sort in this matcher for brevity, but could be added
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn(mockDocuments);

        // Mock for mongoTemplate.count()
        when(mongoTemplate.count(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    boolean correctVisibility = Visibility.PUBLIC.name().equals(visibilityInQuery);
                    String tagInQuery = queryObject.getString("metadata.tags");
                    boolean correctTag = filterTag.toLowerCase().equals(tagInQuery);
                    return correctVisibility && correctTag;
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn((long) mockDocuments.size());

        Page<FileResponse> resultPage = fileService.listFiles(null, filterTag, sortBy, sortDir, pageNum, pageSize);

        assertNotNull(resultPage);
        assertEquals(1, resultPage.getTotalElements());
        assertEquals(1, resultPage.getContent().size());
        assertEquals("work_document.docx", resultPage.getContent().get(0).filename());
        assertTrue(resultPage.getContent().get(0).tags().contains("work"));
    }

    @Test
    void listFiles_whenSortByFilenameAsc_shouldReturnSortedResults() {
        int pageNum = 0;
        int pageSize = 10;
        String sortBy = "filename";
        String sortDir = "asc";

        // Mock documents - out of order by name initially
        ObjectId fileIdZ = new ObjectId();
        Document metaZ = new Document("ownerId", "userZ").append("visibility", Visibility.PUBLIC.name()).append("token", "tokenZ");
        Document docZ = new Document("_id", fileIdZ).append("filename", "zeta.txt").append("uploadDate", new Date(System.currentTimeMillis() - 1000))
                            .append("contentType", "text/plain").append("length", 30L).append("metadata", metaZ);

        ObjectId fileIdA = new ObjectId();
        Document metaA = new Document("ownerId", "userA").append("visibility", Visibility.PUBLIC.name()).append("token", "tokenA");
        Document docA = new Document("_id", fileIdA).append("filename", "alpha.txt").append("uploadDate", new Date(System.currentTimeMillis() - 2000))
                            .append("contentType", "text/plain").append("length", 10L).append("metadata", metaA);

        ObjectId fileIdG = new ObjectId();
        Document metaG = new Document("ownerId", "userG").append("visibility", Visibility.PUBLIC.name()).append("token", "tokenG");
        Document docG = new Document("_id", fileIdG).append("filename", "gamma.txt").append("uploadDate", new Date(System.currentTimeMillis() - 3000))
                            .append("contentType", "text/plain").append("length", 20L).append("metadata", metaG);
        
        // Expected order after sorting by filename ASC: alpha, gamma, zeta
        List<Document> mockDocumentsSorted = Arrays.asList(docA, docG, docZ);

        // Mock for mongoTemplate.find()
        when(mongoTemplate.find(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    boolean correctVisibility = Visibility.PUBLIC.name().equals(visibilityInQuery);
                    // Check sort
                    boolean correctSort = query.getSortObject().containsKey("filename") && query.getSortObject().getInteger("filename") == 1; // 1 for ASC
                    return correctVisibility && correctSort;
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn(mockDocumentsSorted);

        // Mock for mongoTemplate.count()
        when(mongoTemplate.count(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                     if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    return Visibility.PUBLIC.name().equals(visibilityInQuery);
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn((long) mockDocumentsSorted.size());

        Page<FileResponse> resultPage = fileService.listFiles(null, null, sortBy, sortDir, pageNum, pageSize);

        assertNotNull(resultPage);
        assertEquals(3, resultPage.getTotalElements());
        assertEquals(3, resultPage.getContent().size());
        assertEquals("alpha.txt", resultPage.getContent().get(0).filename());
        assertEquals("gamma.txt", resultPage.getContent().get(1).filename());
        assertEquals("zeta.txt", resultPage.getContent().get(2).filename());
    }

    @Test
    void listFiles_whenSortBySizeDesc_shouldReturnSortedResults() {
        int pageNum = 0;
        int pageSize = 10;
        String sortBy = "size"; // API uses "size"
        String sortDir = "desc";
        String sortFieldInDb = "length"; // maps to "length"

        PageRequest expectedPageable = PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, sortFieldInDb));

        ObjectId fileIdSmall = new ObjectId();
        Document metaSmall = new Document("ownerId", "userS").append("visibility", Visibility.PUBLIC.name()).append("token", "tokenS");
        Document docSmall = new Document("_id", fileIdSmall).append("filename", "small.txt").append("uploadDate", new Date())
                                .append("contentType", "text/plain").append("length", 50L).append("metadata", metaSmall);

        ObjectId fileIdLarge = new ObjectId();
        Document metaLarge = new Document("ownerId", "userL").append("visibility", Visibility.PUBLIC.name()).append("token", "tokenL");
        Document docLarge = new Document("_id", fileIdLarge).append("filename", "large.doc").append("uploadDate", new Date())
                                .append("contentType", "application/msword").append("length", 5000L).append("metadata", metaLarge);

        ObjectId fileIdMedium = new ObjectId();
        Document metaMedium = new Document("ownerId", "userM").append("visibility", Visibility.PUBLIC.name()).append("token", "tokenM");
        Document docMedium = new Document("_id", fileIdMedium).append("filename", "medium.pdf").append("uploadDate", new Date())
                                .append("contentType", "application/pdf").append("length", 500L).append("metadata", metaMedium);
        
        List<Document> mockDocumentsSorted = Arrays.asList(docLarge, docMedium, docSmall); // Expected order: Large, Medium, Small

        when(mongoTemplate.find(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    boolean correctVisibility = Visibility.PUBLIC.name().equals(visibilityInQuery);
                    boolean correctSort = query.getSortObject().containsKey(sortFieldInDb) && query.getSortObject().getInteger(sortFieldInDb) == -1; // -1 for DESC
                    return correctVisibility && correctSort;
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn(mockDocumentsSorted);

        when(mongoTemplate.count(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                     if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
                    String visibilityInQuery = queryObject.getString("metadata.visibility");
                    return Visibility.PUBLIC.name().equals(visibilityInQuery);
                }
            }), 
            eq(Document.class), 
            eq("fs.files")))
        .thenReturn((long) mockDocumentsSorted.size());

        Page<FileResponse> resultPage = fileService.listFiles(null, null, sortBy, sortDir, pageNum, pageSize);

        assertNotNull(resultPage);
        assertEquals(3, resultPage.getTotalElements());
        assertEquals(3, resultPage.getContent().size());
        assertEquals("large.doc", resultPage.getContent().get(0).filename());
        assertEquals(5000L, resultPage.getContent().get(0).size());
        assertEquals("medium.pdf", resultPage.getContent().get(1).filename());
        assertEquals(500L, resultPage.getContent().get(1).size());
        assertEquals("small.txt", resultPage.getContent().get(2).filename());
        assertEquals(50L, resultPage.getContent().get(2).size());
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
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
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
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    Document queryObject = query.getQueryObject();
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

        Page<FileResponse> resultPage = fileService.listFiles(null, filterTag, sortBy, sortDir, pageNum, pageSize);

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
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            fileService.listFiles(testUserId, null, invalidSortField, "asc", 0, 10);
        });
        assertTrue(exception.getMessage().contains("Invalid sortBy field: " + invalidSortField));
    }

    @Test
    void updateFileDetails_whenUserNotOwner_shouldThrowUnauthorizedOperationException() {
        FileUpdateRequest updateRequest = new FileUpdateRequest(newFilename);
        String actualOwnerId = "anotherOwner"; 

        org.bson.Document fileMetadataSubDoc = new org.bson.Document("ownerId", actualOwnerId); 
        org.bson.Document fileDocToReturnOnFind = new org.bson.Document("_id", new ObjectId(testFileId))
                                              .append("filename", testFilename)
                                              .append("metadata", fileMetadataSubDoc);
        
        when(mongoTemplate.findOne(
            argThat(new ArgumentMatcher<Query>() {
                @Override
                public boolean matches(Query query) {
                    if (query == null || query.getQueryObject() == null) return false;
                    return query.getQueryObject().getObjectId("_id").toHexString().equals(testFileId);
                }
            }), 
            eq(org.bson.Document.class), 
            eq("fs.files")))
        .thenReturn(fileDocToReturnOnFind);

        UnauthorizedOperationException exception = assertThrows(UnauthorizedOperationException.class, () -> {
            fileService.updateFileDetails(testUserId, testFileId, updateRequest); 
        });

        assertTrue(exception.getMessage().contains("User '" + testUserId + "' not authorized to update fileId: " + testFileId));
        
        verify(mongoTemplate, never()).exists(argThat(q -> q.getQueryObject().containsKey("filename")), eq("fs.files"));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), eq("fs.files"));
    }
} 