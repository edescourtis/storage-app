package com.example.storage_app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.model.Visibility;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@Import({TestcontainersConfiguration.class, MongoTestIndexConfiguration.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class FileStorageIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private GridFsTemplate gridFsTemplate;

    private final String testUserId = "int-test-user-123";

    @AfterEach
    void tearDown() {
        // Clean up database after each test to ensure independence
        mongoTemplate.getDb().getCollection("fs.files").deleteMany(new Document());
        mongoTemplate.getDb().getCollection("fs.chunks").deleteMany(new Document());
    }

    @Test
    void testUploadFile_success_storesDataAndReturnsCorrectResponse() throws Exception {
        String originalFilename = "integration-test-file.txt";
        String requestFilename = "custom-name.txt";
        String tag = "int-tag";
        Visibility visibility = Visibility.PRIVATE;
        byte[] contentBytes = "Integration test content.".getBytes();
        String providedContentType = MediaType.TEXT_PLAIN_VALUE;

        FileUploadRequest uploadRequestDto = new FileUploadRequest(requestFilename, visibility, List.of(tag));
        MockMultipartFile filePart = new MockMultipartFile("file", originalFilename, providedContentType, contentBytes);
        MockMultipartFile propertiesPart = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(uploadRequestDto));

        MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(filePart)
                        .file(propertiesPart)
                        .header("X-User-Id", testUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.filename").value(requestFilename))
                .andExpect(jsonPath("$.visibility").value(visibility.name()))
                .andExpect(jsonPath("$.tags[0]").value(tag.toLowerCase()))
                .andExpect(jsonPath("$.contentType").value(providedContentType))
                .andExpect(jsonPath("$.size").value(contentBytes.length))
                .andExpect(jsonPath("$.downloadLink").exists())
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString();
        FileResponse parsedResponse = objectMapper.readValue(responseBody, FileResponse.class);
        String systemFileUUID = parsedResponse.id();
        assertNotNull(systemFileUUID);
        assertTrue(systemFileUUID.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
        assertEquals(providedContentType, parsedResponse.contentType());

        Query filesQuery = Query.query(Criteria.where("filename").is(systemFileUUID));
        Document fileDoc = mongoTemplate.findOne(filesQuery, Document.class, "fs.files");
        assertNotNull(fileDoc, "File document not found in fs.files using system UUID");
        
        assertEquals(systemFileUUID, fileDoc.getString("filename"));
        assertEquals((long) contentBytes.length, fileDoc.getLong("length").longValue());
        assertNull(fileDoc.getString("contentType"));
        
        Document metadata = fileDoc.get("metadata", Document.class);
        assertNotNull(metadata);
        assertEquals(providedContentType, metadata.getString("contentType"), "contentType in metadata should be set correctly");
        assertEquals(testUserId, metadata.getString("ownerId"));
        assertEquals(visibility.name(), metadata.getString("visibility"));
        assertEquals(requestFilename, metadata.getString("originalFilename")); 
        assertTrue(metadata.getList("tags", String.class).contains(tag.toLowerCase()));
        assertNotNull(metadata.getString("token"));
        assertNotNull(metadata.getString("sha256"));

        ObjectId actualFileObjectId = fileDoc.getObjectId("_id");
        assertNotNull(actualFileObjectId, "_id not found in retrieved fileDoc");
        Query chunksQuery = Query.query(Criteria.where("files_id").is(actualFileObjectId));
        assertTrue(mongoTemplate.exists(chunksQuery, "fs.chunks"), "Chunks not found for file");
    }

    @Test
    void testUploadFile_duplicateFilenameForUser_returnsConflict() throws Exception {
        String commonFilename = "duplicate-name-test.txt";
        byte[] content1 = "Content for file 1".getBytes();
        byte[] content2 = "Content for file 2 (different)".getBytes();

        FileUploadRequest request1 = new FileUploadRequest(commonFilename, Visibility.PUBLIC, List.of("r1"));
        MockMultipartFile filePart1 = new MockMultipartFile("file", "original1.txt", "text/plain", content1);
        MockMultipartFile propertiesPart1 = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(request1));

        // First upload - should succeed
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(filePart1)
                        .file(propertiesPart1)
                        .header("X-User-Id", testUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Second upload with same filename and user - should fail
        FileUploadRequest request2 = new FileUploadRequest(commonFilename, Visibility.PRIVATE, List.of("r2"));
        MockMultipartFile filePart2 = new MockMultipartFile("file", "original2.txt", "text/plain", content2);
        MockMultipartFile propertiesPart2 = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(request2));

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(filePart2)
                        .file(propertiesPart2)
                        .header("X-User-Id", testUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Filename '" + commonFilename + "' already exists for this user."));

        Query filesQuery = Query.query(
            Criteria.where("metadata.originalFilename").is(commonFilename)
                    .and("metadata.ownerId").is(testUserId)
        );
        long count = mongoTemplate.count(filesQuery, "fs.files");
        assertEquals(1, count, "Should only be one file with the conflicting originalFilename in DB for this user");
    }

    @Test
    void testUploadFile_duplicateContentForUser_returnsConflict() throws Exception {
        String filename1 = "content-test-file1.txt";
        String filename2 = "content-test-file2.txt";
        byte[] commonContent = "This is common content for duplicate check.".getBytes();

        FileUploadRequest request1 = new FileUploadRequest(filename1, Visibility.PUBLIC, List.of("c1"));
        MockMultipartFile filePart1 = new MockMultipartFile("file", "original_c1.txt", "text/plain", commonContent);
        MockMultipartFile propertiesPart1 = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(request1));

        MvcResult result1 = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(filePart1)
                        .file(propertiesPart1)
                        .header("X-User-Id", testUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        FileResponse response1 = objectMapper.readValue(result1.getResponse().getContentAsString(), FileResponse.class);
        String fileId1_systemUUID = response1.id();

        FileUploadRequest request2 = new FileUploadRequest(filename2, Visibility.PRIVATE, List.of("c2"));
        MockMultipartFile filePart2 = new MockMultipartFile("file", "original_c2.txt", "text/plain", commonContent);
        MockMultipartFile propertiesPart2 = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(request2));

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(filePart2)
                        .file(propertiesPart2)
                        .header("X-User-Id", testUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict()) // Expect 409 Conflict
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Content already exists for this user (hash conflict")));
        
        // Verify DB: only the first file should exist for this user with this content hash (implicitly)
        // Check that fileId1_systemUUID still exists by its system UUID (stored in filename field)
        assertTrue(mongoTemplate.exists(Query.query(Criteria.where("filename").is(fileId1_systemUUID)), "fs.files"),
                   "File document for the first upload should still exist, queried by its system UUID.");
        
        // Check that no file with originalFilename=filename2 exists for this user (as its upload was aborted)
        Query secondFileQuery = Query.query(
            Criteria.where("metadata.originalFilename").is(filename2)
                    .and("metadata.ownerId").is(testUserId)
        );
        assertFalse(mongoTemplate.exists(secondFileQuery, "fs.files"),
                    "File with originalFilename '" + filename2 + "' for this user should not exist after failed upload.");
    }

    @Test
    void testUpdateFilename_success_updatesInDB() throws Exception {
        // 1. Upload initial file
        String originalFilename = "original-for-update.txt";
        String initialRequestFilename = "initial-name.txt";
        byte[] contentBytes = "Update test content.".getBytes();
        FileUploadRequest uploadRequestDto = new FileUploadRequest(initialRequestFilename, Visibility.PRIVATE, List.of("update-me"));
        MockMultipartFile filePart = new MockMultipartFile("file", originalFilename, "text/plain", contentBytes);
        MockMultipartFile propertiesPart = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(uploadRequestDto));

        MvcResult uploadResult = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(filePart)
                        .file(propertiesPart)
                        .header("X-User-Id", testUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        FileResponse uploadedFileResponse = objectMapper.readValue(uploadResult.getResponse().getContentAsString(), FileResponse.class);
        String fileId = uploadedFileResponse.id();

        // 2. Update the filename
        String newFilename = "updated-successfully.txt";
        FileUpdateRequest updateRequestDto = new FileUpdateRequest(newFilename);

        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/files/{fileId}", fileId)
                        .header("X-User-Id", testUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequestDto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value(newFilename));

        // 3. Verify in DB
        Query findBySystemUuidQuery = Query.query(Criteria.where("filename").is(fileId));
        Document updatedFileDoc = mongoTemplate.findOne(findBySystemUuidQuery, Document.class, "fs.files");
        assertNotNull(updatedFileDoc, "Updated file document not found in DB using system UUID");
        
        // Verify the system UUID (filename field) is unchanged
        assertEquals(fileId, updatedFileDoc.getString("filename"));
        // Verify the user-provided filename is updated in metadata
        Document metadata = updatedFileDoc.get("metadata", Document.class);
        assertNotNull(metadata, "Metadata not found in updated document");
        assertEquals(newFilename, metadata.getString("originalFilename"));
    }

    @Test
    void testUpdateFilename_toExistingNameForUser_returnsConflict() throws Exception {
        // 1. Upload file1
        String filename1 = "file1-for-conflict-update.txt";
        FileUploadRequest uploadRequest1 = new FileUploadRequest(filename1, Visibility.PRIVATE, List.of("f1"));
        MockMultipartFile filePart1 = new MockMultipartFile("file", "original1.txt", "text/plain", "content1".getBytes());
        MockMultipartFile propertiesPart1 = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(uploadRequest1));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files").file(filePart1).file(propertiesPart1).header("X-User-Id", testUserId)).andExpect(status().isCreated());

        // 2. Upload file2 (this will be the one we try to rename)
        String filename2Initial = "file2-for-conflict-update.txt";
        FileUploadRequest uploadRequest2 = new FileUploadRequest(filename2Initial, Visibility.PRIVATE, List.of("f2"));
        MockMultipartFile filePart2 = new MockMultipartFile("file", "original2.txt", "text/plain", "content2".getBytes());
        MockMultipartFile propertiesPart2 = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(uploadRequest2));
        MvcResult uploadResult2 = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files").file(filePart2).file(propertiesPart2).header("X-User-Id", testUserId)).andExpect(status().isCreated()).andReturn();
        String fileId2 = objectMapper.readValue(uploadResult2.getResponse().getContentAsString(), FileResponse.class).id();

        // 3. Attempt to update file2's name to file1's name
        FileUpdateRequest updateRequestDto = new FileUpdateRequest(filename1); // Try to rename file2 to filename1

        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/files/{fileId}", fileId2)
                        .header("X-User-Id", testUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequestDto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict()) 
                .andExpect(jsonPath("$.message").value("Filename '" + filename1 + "' already exists for this user."));

        // Verify file2 still has its original name in DB
        Query findFile2BySystemUuidQuery = Query.query(Criteria.where("filename").is(fileId2));
        Document file2Doc = mongoTemplate.findOne(findFile2BySystemUuidQuery, Document.class, "fs.files");
        assertNotNull(file2Doc, "File2 document not found in DB using its system UUID");
        
        assertEquals(fileId2, file2Doc.getString("filename"), "System UUID of file2 should match");
        Document metadata2 = file2Doc.get("metadata", Document.class);
        assertNotNull(metadata2, "Metadata for file2 should exist");
        assertEquals(filename2Initial, metadata2.getString("originalFilename"), "Original user filename of file2 should be unchanged.");
    }

    @Test
    void testDeleteFile_success_removesFromDB() throws Exception {
        // 1. Upload a file
        FileUploadRequest uploadRequestDto = new FileUploadRequest("to-be-deleted.txt", Visibility.PUBLIC, List.of("delete-me"));
        MockMultipartFile filePart = new MockMultipartFile("file", "delete_original.txt", "text/plain", "delete content".getBytes());
        MockMultipartFile propertiesPart = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(uploadRequestDto));
        MvcResult uploadResult = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(filePart)
                        .file(propertiesPart)
                        .header("X-User-Id", testUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId_systemUUID = objectMapper.readValue(uploadResult.getResponse().getContentAsString(), FileResponse.class).id();

        // 2. Delete the file (endpoint uses the system UUID)
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/files/{fileId}", fileId_systemUUID)
                        .header("X-User-Id", testUserId))
                .andExpect(status().isNoContent());

        // 3. Verify not in fs.files by querying with the system UUID (filename field)
        Query fileQuery = Query.query(Criteria.where("filename").is(fileId_systemUUID));
        assertFalse(mongoTemplate.exists(fileQuery, "fs.files"), 
                    "File document should be deleted from fs.files (queried by system UUID in filename field)");
        
        // Note: Verifying fs.chunks deletion is implicitly handled by GridFS when fs.files doc is deleted.
        // To explicitly verify, one would need the ObjectId _id of the fs.files doc before deletion.
    }

    @Test
    void testDeleteFile_unauthorizedUser_returnsForbiddenAndFileRemains() throws Exception {
        // 1. User1 uploads a file
        String user1 = "user1-owner";
        String user2 = "user2-attacker";
        FileUploadRequest uploadRequestDto = new FileUploadRequest("owned-by-user1.txt", Visibility.PRIVATE, List.of("owned"));
        MockMultipartFile filePart = new MockMultipartFile("file", "owned.txt", "text/plain", "content".getBytes());
        MockMultipartFile propertiesPart = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(uploadRequestDto));
        
        MvcResult uploadResult = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(filePart)
                        .file(propertiesPart)
                        .header("X-User-Id", user1)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId_systemUUID = objectMapper.readValue(uploadResult.getResponse().getContentAsString(), FileResponse.class).id();

        // 2. User2 attempts to delete User1's file using the system UUID
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/files/{fileId}", fileId_systemUUID)
                        .header("X-User-Id", user2)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden()) 
                .andExpect(jsonPath("$.message").value("User '" + user2 + "' not authorized to delete fileId: " + fileId_systemUUID));

        // 3. Verify file still exists in DB by querying with the system UUID (filename field)
        Query fileQuery = Query.query(Criteria.where("filename").is(fileId_systemUUID));
        assertTrue(mongoTemplate.exists(fileQuery, "fs.files"), 
                   "File should still exist after unauthorized delete attempt (queried by system UUID in filename field)");
    }

    @Test
    void testListFiles_publicAndUserSpecific_withFiltersAndPagination() throws Exception {
        // Setup: Upload a diverse set of files
        String user1 = "list-user-1";
        String user2 = "list-user-2";

        // User1 files
        uploadHelper(user1, "user1file1.txt", "text/plain", "content1", Visibility.PRIVATE, List.of("tagA", "common"));
        uploadHelper(user1, "user1file2.pdf", "application/pdf", "content2", Visibility.PUBLIC, List.of("tagB", "common"));
        uploadHelper(user1, "user1file3.jpg", "image/jpeg", "content3", Visibility.PRIVATE, List.of("tagA"));

        // User2 files
        uploadHelper(user2, "user2file1.txt", "text/plain", "content4", Visibility.PRIVATE, List.of("tagB"));
        uploadHelper(user2, "user2file2.png", "image/png", "content5", Visibility.PUBLIC, List.of("tagC", "common"));

        // Test 1: List all PUBLIC files (no user, no tag) - expect user1file2.pdf, user2file2.png
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/files")
                        .param("visibility", "PUBLIC") // Assuming a way to request only public if no X-User-Id
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.filename == 'user1file2.pdf')]").exists())
                .andExpect(jsonPath("$.content[?(@.filename == 'user2file2.png')]").exists());

        // Test 2: List files for user1 (no tag) - expect user1file1, user1file2, user1file3
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/files")
                        .header("X-User-Id", user1)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        // Test 3: List files for user1 with tag "tagA" - expect user1file1, user1file3
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/files")
                        .header("X-User-Id", user1)
                        .param("tag", "tagA")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.filename == 'user1file1.txt')]").exists())
                .andExpect(jsonPath("$.content[?(@.filename == 'user1file3.jpg')]").exists());

        // Test 4: List PUBLIC files with tag "common", sorted by filename ASC, paginated
        // Expect user1file2.pdf, user2file2.png (user1file2.pdf should be first)
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/files")
                        .param("visibility", "PUBLIC")
                        .param("tag", "CoMmOn") // Test case-insensitivity for tag
                        .param("sortBy", "filename")
                        .param("sortDir", "asc")
                        .param("page", "0")
                        .param("size", "1") 
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].filename").value("user1file2.pdf"));
        
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/files")
                        .param("visibility", "PUBLIC")
                        .param("tag", "common")
                        .param("sortBy", "filename")
                        .param("sortDir", "asc")
                        .param("page", "1")
                        .param("size", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].filename").value("user2file2.png"));
    }

    // Helper method for uploads in this test class
    private FileResponse uploadHelper(String userId, String filename, String contentType, String content, Visibility visibility, List<String> tags) throws Exception {
        FileUploadRequest request = new FileUploadRequest(filename, visibility, tags);
        MockMultipartFile filePart = new MockMultipartFile("file", "original-" + filename, contentType, content.getBytes());
        MockMultipartFile propertiesPart = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(request));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(filePart)
                        .file(propertiesPart)
                        .header("X-User-Id", userId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), FileResponse.class);
    }

    @Test
    void testUploadFile_parallel_sameFilename_handlesConcurrency() throws Exception {
        String commonFilename = "parallel-filename-test.txt";
        String userId = "parallel-user-1";
        int numberOfThreads = 3;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numberOfThreads);
        java.util.List<java.util.concurrent.Future<MvcResult>> results = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger conflictCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger otherErrorCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            int taskNumber = i;
            java.util.concurrent.Callable<MvcResult> task = () -> {
                FileUploadRequest request = new FileUploadRequest(commonFilename, Visibility.PRIVATE, List.of("parallel", "task" + taskNumber));
                MockMultipartFile filePart = new MockMultipartFile("file", "original" + taskNumber + ".txt", "text/plain", ("content" + taskNumber).getBytes());
                MockMultipartFile propertiesPart = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(request));
                
                return mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                                .file(filePart)
                                .file(propertiesPart)
                                .header("X-User-Id", userId)
                                .accept(MediaType.APPLICATION_JSON))
                        .andReturn();
            };
            results.add(executor.submit(task));
        }

        for (java.util.concurrent.Future<MvcResult> future : results) {
            MvcResult result = future.get(); 
            if (result.getResponse().getStatus() == org.springframework.http.HttpStatus.CREATED.value()) {
                successCount.incrementAndGet();
            } else if (result.getResponse().getStatus() == org.springframework.http.HttpStatus.CONFLICT.value()) {
                conflictCount.incrementAndGet();
                String responseContent = result.getResponse().getContentAsString();
                String expectedMessageSubstring1 = "Filename '" + commonFilename + "' already exists for this user."; // From pre-check
                String expectedMessageSubstring2 = "Filename '" + commonFilename + "' already exists for this user (DB conflict during store)."; // From DKE on store
                assertTrue(responseContent.contains(expectedMessageSubstring1) || responseContent.contains(expectedMessageSubstring2),
                    "Conflict message for filename should contain '" + expectedMessageSubstring1 + "' or '" + expectedMessageSubstring2 + "'. Was: " + responseContent);
            } else {
                otherErrorCount.incrementAndGet();
                System.err.println("Parallel test got unexpected status: " + result.getResponse().getStatus() + " with body: " + result.getResponse().getContentAsString());
            }
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, successCount.get(), "Exactly one upload should succeed for parallel filename attempts.");
        assertEquals(numberOfThreads - 1, conflictCount.get(), "The other parallel filename attempts should result in conflict.");
        assertEquals(0, otherErrorCount.get(), "There should be no other errors in parallel filename attempts.");

        // Verify that eventually only one file with this originalFilename exists for the user and is not PENDING
        Query filesQuery = Query.query(
            Criteria.where("metadata.originalFilename").is(commonFilename)
                    .and("metadata.ownerId").is(userId)
                    // .and("metadata.visibility").ne(Visibility.PENDING.name()) // PENDING no longer exists
        );
        long dbCount = mongoTemplate.count(filesQuery, "fs.files");
        assertEquals(1, dbCount, "Only one file with that originalFilename should be in the DB for the user eventually.");
    }

    @Test
    void testUploadFile_parallel_sameContent_handlesConcurrency() throws Exception {
        String userId = "parallel-user-2";
        byte[] commonContent = "identical-parallel-content".getBytes();
        int numberOfThreads = 3;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numberOfThreads);
        java.util.List<java.util.concurrent.Future<MvcResult>> results = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger conflictCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger otherErrorCount = new java.util.concurrent.atomic.AtomicInteger(0);
        List<String> successfulFileIds = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numberOfThreads; i++) {
            String filename = "parallel-content-file-" + i + ".txt"; 
            java.util.concurrent.Callable<MvcResult> task = () -> {
                FileUploadRequest request = new FileUploadRequest(filename, Visibility.PRIVATE, List.of("parallel-content"));
                MockMultipartFile filePart = new MockMultipartFile("file", "original-" + filename, "text/plain", commonContent);
                MockMultipartFile propertiesPart = new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(request));
                
                return mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                                .file(filePart)
                                .file(propertiesPart)
                                .header("X-User-Id", userId)
                                .accept(MediaType.APPLICATION_JSON))
                        .andReturn();
            };
            results.add(executor.submit(task));
        }

        for (java.util.concurrent.Future<MvcResult> future : results) {
            MvcResult result = future.get();
            if (result.getResponse().getStatus() == org.springframework.http.HttpStatus.CREATED.value()) {
                successCount.incrementAndGet();
                FileResponse fr = objectMapper.readValue(result.getResponse().getContentAsString(), FileResponse.class);
                if (fr != null && fr.id() != null) successfulFileIds.add(fr.id());
            } else if (result.getResponse().getStatus() == org.springframework.http.HttpStatus.CONFLICT.value()) {
                conflictCount.incrementAndGet();
                String responseContent = result.getResponse().getContentAsString();
                String expectedMsgHashConflict = "Content already exists for this user (hash conflict"; // From update stage DKE
                String expectedMsgStoreConflict = "Content already exists for this user (DB conflict during store)"; // From store stage DKE (if (ownerId, null sha256) conflicts via non-sparse index)
                assertTrue(responseContent.contains(expectedMsgHashConflict) || responseContent.contains(expectedMsgStoreConflict),
                    "Conflict message for content should contain '" + expectedMsgHashConflict + "' or '" + expectedMsgStoreConflict + "'. Was: " + responseContent);
            } else {
                otherErrorCount.incrementAndGet();
            }
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, successCount.get(), "Exactly one upload should succeed for parallel content attempts.");
        assertEquals(numberOfThreads - 1, conflictCount.get(), "The other parallel content attempts should result in conflict.");
        assertEquals(0, otherErrorCount.get(), "There should be no other errors in parallel content attempts.");
        
        // Verify that all successfully uploaded files (if more than 1 due to race) have the same content hash
        // And that overall, for this user, there's only one unique content hash for this specific content.
        if (!successfulFileIds.isEmpty()) {
            String firstHash = null;
            int filesWithThisContentHash = 0;
            for (String fileId_systemUUID : successfulFileIds) {
                Query findBySystemUUIDQuery = Query.query(
                    Criteria.where("filename").is(fileId_systemUUID)
                            .and("metadata.ownerId").is(userId)
                            // .and("metadata.visibility").ne(Visibility.PENDING.name()) // PENDING no longer exists
                );
                Document fileDoc = mongoTemplate.findOne(findBySystemUUIDQuery, Document.class, "fs.files");
                if (fileDoc != null) {
                    Document meta = fileDoc.get("metadata", Document.class);
                    if (meta != null && meta.getString("sha256") != null) {
                        if (firstHash == null) {
                            firstHash = meta.getString("sha256");
                        }
                        assertEquals(firstHash, meta.getString("sha256"), "All successfully uploaded files of same content must have the same hash.");
                    }
                }
            }
            if (firstHash != null) {
                 Query hashQuery = Query.query(Criteria.where("metadata.ownerId").is(userId).and("metadata.sha256").is(firstHash)); // Removed PENDING check
                 filesWithThisContentHash = (int) mongoTemplate.count(hashQuery, "fs.files");
                 assertEquals(1, filesWithThisContentHash, "Only one file entry should ultimately exist for this content hash and user.");
            } else if (successCount.get() == 0) { // If no success, all must have conflicted correctly
                assertEquals(numberOfThreads, conflictCount.get(), "If no success, all threads should have conflicted on content.");
            } else {
                fail("Inconsistent state: some uploads succeeded but could not verify content hash, or not all non-succeeded uploads were conflicts.");
            }
        } else if (conflictCount.get() < numberOfThreads) { // Should be successCount=1, conflictCount=N-1
             fail("No uploads succeeded, but not all conflicted due to content. Success: " + successCount.get() + ", Conflict: " + conflictCount.get());
        }
    }

    @Test
    void testUploadFile_simulateLargeFile_storesCorrectMetadata() throws Exception {
        String userFilename = "large-file-simulation.big";
        long reportedSize = 2L * 1024 * 1024 * 1024; // 2GB
        byte[] smallActualContent = "This is a small stand-in for a large file.".getBytes();
        FileUploadRequest uploadRequestDto = new FileUploadRequest(userFilename, Visibility.PUBLIC, List.of("largefile"));
        
        // Tika will likely detect this small string as text/plain
        String expectedDetectedContentTypeByService = MediaType.TEXT_PLAIN_VALUE; 

        MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/files")
                        .file(new MockMultipartFile(
                                "file", 
                                "original_large.big", // original multipart filename 
                                "application/octet-stream", // content type in multipart req
                                smallActualContent) {
                            @Override
                            public long getSize() {
                                return reportedSize; // Service uses this for metadata.size
                            }
                        })
                        .file(new MockMultipartFile("properties", null, MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(uploadRequestDto)))
                        .header("X-User-Id", testUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value(userFilename))
                .andExpect(jsonPath("$.contentType").value(expectedDetectedContentTypeByService)) 
                .andExpect(jsonPath("$.size").value(reportedSize)) 
                .andReturn();

        FileResponse parsedResponse = objectMapper.readValue(mvcResult.getResponse().getContentAsString(), FileResponse.class);
        String systemFileUUID = parsedResponse.id(); 
        assertNotNull(systemFileUUID);
        assertEquals(expectedDetectedContentTypeByService, parsedResponse.contentType());

        Document fileDoc = mongoTemplate.findOne(Query.query(Criteria.where("filename").is(systemFileUUID)), Document.class, "fs.files");
        assertNotNull(fileDoc, "File document not found using system UUID");
        assertEquals((long) smallActualContent.length, fileDoc.getLong("length").longValue(), "DB length should be actual content size");
        assertNull(fileDoc.getString("contentType")); // Expect null for top-level DB contentType
        
        Document metadata = fileDoc.get("metadata", Document.class);
        assertNotNull(metadata);
        assertEquals(userFilename, metadata.getString("originalFilename"));
        assertEquals(expectedDetectedContentTypeByService, metadata.getString("contentType"), "contentType in metadata should be correct");
        assertEquals(reportedSize, metadata.getLong("size").longValue(), "Metadata size should be reported size");
    }
} 