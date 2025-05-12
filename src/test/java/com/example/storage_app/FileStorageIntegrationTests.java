package com.example.storage_app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.model.Visibility;
import com.example.storage_app.repository.FileRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest
@Import({TestcontainersConfiguration.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class FileStorageIntegrationTests {

  private static final Logger log = LoggerFactory.getLogger(FileStorageIntegrationTests.class);

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private GridFsTemplate gridFsTemplate;

  @Autowired private FileRecordRepository fileRecordRepository;

  private final String testUserId = "int-test-user-123";

  @BeforeEach
  void ensureIndexesBeforeTest() {
    log.info("--- @BeforeEach: START ---");
    logAllMongoDocuments("Before ensureIndexesBeforeTest - fs.files");
    logAllMongoDocuments("Before ensureIndexesBeforeTest - fs.chunks");

    // Ensure unique indexes are present before any test logic runs.
    // This is critical for replica set/test reliability: if the collection is dropped, indexes are
    // lost,
    // and in a replica set, index creation may lag behind. By recreating indexes synchronously
    // here,
    // we guarantee deduplication and avoid race conditions.
    MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext =
        mongoTemplate.getConverter().getMappingContext();
    MongoPersistentEntityIndexResolver resolver =
        new MongoPersistentEntityIndexResolver(mappingContext);
    resolver
        .resolveIndexFor(com.example.storage_app.model.FileRecord.class)
        .forEach(
            index -> {
              log.info("Ensuring index: {} on fs.files", index.getIndexKeys().toJson());
              mongoTemplate
                  .indexOps(com.example.storage_app.model.FileRecord.class)
                  .ensureIndex(index);
            });
    logAllMongoIndexes("After ensureIndexesBeforeTest - fs.files");
    log.info("--- @BeforeEach: END ---");
  }

  @AfterEach
  void tearDown() {
    log.info("--- @AfterEach: START ---");
    logAllMongoDocuments("Before tearDown drop - fs.files");
    logAllMongoDocuments("Before tearDown drop - fs.chunks");
    mongoTemplate.getDb().getCollection("fs.files").drop();
    mongoTemplate.getDb().getCollection("fs.chunks").drop();
    log.info("Dropped fs.files and fs.chunks collections.");
    logAllMongoDocuments("After tearDown drop - fs.files (should be empty)");
    logAllMongoDocuments("After tearDown drop - fs.chunks (should be empty)");
    logAllMongoIndexes("After tearDown drop - fs.files (indexes might be gone)");
    log.info("--- @AfterEach: END ---");
  }

  // Helper method to log documents from a given collection
  private void logAllMongoDocuments(String contextMessage) {
    log.info("MongoDB documents for [{}]:", contextMessage);
    try {
      List<Document> fileDocs =
          mongoTemplate.getCollection("fs.files").find().into(new ArrayList<>());
      if (fileDocs.isEmpty()) {
        log.info("  fs.files: No documents found.");
      } else {
        fileDocs.forEach(doc -> log.info("  fs.files doc: {}", doc.toJson()));
      }
      List<Document> chunkDocs =
          mongoTemplate.getCollection("fs.chunks").find().into(new ArrayList<>());
      if (chunkDocs.isEmpty()) {
        log.info("  fs.chunks: No documents found.");
      } else {
        chunkDocs.forEach(doc -> log.info("  fs.chunks doc: {}", doc.toJson()));
      }
    } catch (Exception e) {
      log.error("Error logging MongoDB documents for [{}]: {}", contextMessage, e.getMessage(), e);
    }
  }

  // Helper method to log indexes from a given collection
  private void logAllMongoIndexes(String contextMessage) {
    log.info("MongoDB indexes for [{}]:", contextMessage);
    try {
      List<Document> indexes =
          mongoTemplate.getDb().getCollection("fs.files").listIndexes().into(new ArrayList<>());
      if (indexes.isEmpty()) {
        log.info("  fs.files: No indexes found (this is expected if collection was just dropped).");
      } else {
        indexes.forEach(idx -> log.info("  fs.files index: {}", idx.toJson()));
      }
    } catch (Exception e) {
      log.error("Error logging MongoDB indexes for [{}]: {}", contextMessage, e.getMessage(), e);
    }
  }

  @Test
  void testUploadFile_success_storesDataAndReturnsCorrectResponse() throws Exception {
    String uniqueRequestFilename = "success-upload-" + UUID.randomUUID() + ".txt";
    log.info(
        "testUploadFile_success: Attempting upload with uniqueRequestFilename: {}",
        uniqueRequestFilename);
    String tag = "int-tag";
    Visibility visibility = Visibility.PRIVATE;
    byte[] contentBytes = "Integration test content for success.".getBytes();
    String providedContentType = MediaType.TEXT_PLAIN_VALUE;

    FileUploadRequest uploadRequestDto =
        new FileUploadRequest(uniqueRequestFilename, visibility, List.of(tag));
    MockMultipartFile filePart =
        new MockMultipartFile("file", uniqueRequestFilename, providedContentType, contentBytes);
    MockMultipartFile propertiesPart =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(uploadRequestDto));

    MvcResult mvcResult =
        mockMvc
            .perform(
                MockMvcRequestBuilders.multipart("/api/v1/files")
                    .file(filePart)
                    .file(propertiesPart)
                    .header("X-User-Id", testUserId)
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.filename").value(uniqueRequestFilename))
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
    assertTrue(
        systemFileUUID.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
    assertEquals(providedContentType, parsedResponse.contentType());

    Query filesQuery = Query.query(Criteria.where("filename").is(systemFileUUID));
    Document fileDoc = mongoTemplate.findOne(filesQuery, Document.class, "fs.files");
    assertNotNull(fileDoc, "File document not found in fs.files using system UUID");

    assertEquals(systemFileUUID, fileDoc.getString("filename"));
    assertEquals((long) contentBytes.length, fileDoc.getLong("length").longValue());
    assertNull(fileDoc.getString("contentType"));

    Document metadata = fileDoc.get("metadata", Document.class);
    assertNotNull(metadata);
    assertEquals(
        providedContentType,
        metadata.getString("contentType"),
        "contentType in metadata should be set correctly");
    assertEquals(testUserId, metadata.getString("ownerId"));
    assertEquals(visibility.name(), metadata.getString("visibility"));
    assertEquals(uniqueRequestFilename, metadata.getString("originalFilename"));
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
    String commonOriginalFilename = "duplicate-name-test-" + UUID.randomUUID() + ".txt";
    log.info(
        "testUploadFile_duplicateFilename: commonOriginalFilename for test: {}",
        commonOriginalFilename);
    byte[] content1 = "Content for file 1".getBytes();
    byte[] content2 = "Content for file 2 (different)".getBytes();

    FileUploadRequest request1 =
        new FileUploadRequest(commonOriginalFilename, Visibility.PUBLIC, List.of("r1"));
    MockMultipartFile filePart1 =
        new MockMultipartFile("file", commonOriginalFilename, "text/plain", content1);
    MockMultipartFile propertiesPart1 =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(request1));

    log.info(
        "testUploadFile_duplicateFilename: Performing first upload with filename: {}",
        request1.filename());
    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart1)
                .file(propertiesPart1)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated());

    FileUploadRequest request2 =
        new FileUploadRequest(commonOriginalFilename, Visibility.PRIVATE, List.of("r2"));
    MockMultipartFile filePart2 =
        new MockMultipartFile("file", commonOriginalFilename, "text/plain", content2);
    MockMultipartFile propertiesPart2 =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(request2));

    log.info(
        "testUploadFile_duplicateFilename: Performing second upload (expect conflict) with filename: {}",
        request2.filename());
    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart2)
                .file(propertiesPart2)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("Filename '" + commonOriginalFilename + "' already exists for this user."));

    Query filesQuery =
        Query.query(
            Criteria.where("metadata.originalFilename")
                .is(commonOriginalFilename)
                .and("metadata.ownerId")
                .is(testUserId));
    long count = mongoTemplate.count(filesQuery, "fs.files");
    assertEquals(
        1,
        count,
        "Should only be one file with the conflicting originalFilename in DB for this user");
  }

  @Test
  void testUploadFile_duplicateContentForUser_returnsConflict() throws Exception {
    String filename1 = "content-dup-1-" + UUID.randomUUID() + ".txt";
    String filename2 = "content-dup-2-" + UUID.randomUUID() + ".txt";
    log.info("testUploadFile_duplicateContent: filename1: {}, filename2: {}", filename1, filename2);
    byte[] commonContent = "This is common content for duplicate check.".getBytes();

    FileUploadRequest request1 = new FileUploadRequest(filename1, Visibility.PUBLIC, List.of("c1"));
    MockMultipartFile filePart1 =
        new MockMultipartFile("file", filename1, "text/plain", commonContent);
    MockMultipartFile propertiesPart1 =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(request1));

    log.info(
        "testUploadFile_duplicateContent: Performing first upload with filename1: {}",
        request1.filename());
    MvcResult result1 =
        mockMvc
            .perform(
                MockMvcRequestBuilders.multipart("/api/v1/files")
                    .file(filePart1)
                    .file(propertiesPart1)
                    .header("X-User-Id", testUserId)
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
    FileResponse response1 =
        objectMapper.readValue(result1.getResponse().getContentAsString(), FileResponse.class);
    String fileId1_systemUUID = response1.id();

    FileUploadRequest request2 =
        new FileUploadRequest(filename2, Visibility.PRIVATE, List.of("c2"));
    MockMultipartFile filePart2 =
        new MockMultipartFile("file", filename2, "text/plain", commonContent);
    MockMultipartFile propertiesPart2 =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(request2));

    log.info(
        "testUploadFile_duplicateContent: Performing second upload (expect conflict) with filename2: {}",
        request2.filename());
    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart2)
                .file(propertiesPart2)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Content with hash")))
        .andExpect(
            jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("already exists for this user.")));

    assertTrue(
        mongoTemplate.exists(
            Query.query(Criteria.where("filename").is(fileId1_systemUUID)), "fs.files"),
        "File document for the first upload should still exist, queried by its system UUID.");

    Query secondFileQuery =
        Query.query(
            Criteria.where("metadata.originalFilename")
                .is(filename2)
                .and("metadata.ownerId")
                .is(testUserId));
    assertFalse(
        mongoTemplate.exists(secondFileQuery, "fs.files"),
        "File with originalFilename \'"
            + filename2
            + "\' for this user should not exist after failed upload.");
  }

  @Test
  void testUpdateFilename_success_updatesInDB() throws Exception {
    String originalFilename_setup = "original-for-update-" + UUID.randomUUID() + ".txt";
    String newFilename_target = "updated-successfully-" + UUID.randomUUID() + ".txt";
    String userIdToUse = testUserId + "-updateSuccess"; // Isolate user for this test

    // Setup: Upload the initial file
    FileResponse uploadedFileResponse =
        uploadHelper(
            userIdToUse,
            originalFilename_setup,
            "text/plain",
            "Update test content",
            Visibility.PRIVATE,
            List.of("update-me"));
    String fileId = uploadedFileResponse.id(); // This is the system UUID

    FileUpdateRequest updateRequestDto_target = new FileUpdateRequest(newFilename_target);

    // Act: Perform the PATCH request to update the filename
    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/files/{fileId}", fileId)
                .header("X-User-Id", userIdToUse) // Use the same user who uploaded
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequestDto_target))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk()) // Expect 200 OK for successful update
        .andExpect(jsonPath("$.filename").value(newFilename_target));

    // Assert: Check the database for the updated filename
    // Query by filename (which stores the system UUID) as FileRecord.id maps to it
    Query findBySystemUuidQuery = Query.query(Criteria.where("filename").is(fileId));
    Document updatedFileDoc =
        mongoTemplate.findOne(findBySystemUuidQuery, Document.class, "fs.files");
    assertNotNull(updatedFileDoc, "Updated file document not found in DB using system UUID");

    assertEquals(fileId, updatedFileDoc.getString("filename")); // System filename (UUID) unchanged
    Document metadata = updatedFileDoc.get("metadata", Document.class);
    assertNotNull(metadata, "Metadata not found in updated document");
    assertEquals(
        newFilename_target,
        metadata.getString("originalFilename"),
        "User-provided filename should be updated in metadata");
    assertEquals(userIdToUse, metadata.getString("ownerId"));
  }

  @Test
  void testUpdateFilename_toExistingNameForUser_returnsConflict() throws Exception {
    String userForThisTest = testUserId + "-updateConflict";
    String filename1_setup = "file1-for-conflict-update-" + UUID.randomUUID() + ".txt";
    uploadHelper(
        userForThisTest,
        filename1_setup,
        "text/plain",
        "content1-update",
        Visibility.PRIVATE,
        List.of("f1"));

    String filename2Initial_setup = "file2-for-conflict-update-" + UUID.randomUUID() + ".txt";
    FileResponse file2Response =
        uploadHelper(
            userForThisTest,
            filename2Initial_setup,
            "text/plain",
            "content2-update",
            Visibility.PRIVATE,
            List.of("f2"));
    String fileId2_systemUUID = file2Response.id();

    FileUpdateRequest updateRequestDto_target =
        new FileUpdateRequest(filename1_setup); // Attempt to rename to filename1_setup

    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/files/{fileId}", fileId2_systemUUID)
                .header("X-User-Id", userForThisTest)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequestDto_target))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict()) // Expect 409 Conflict
        .andExpect(
            jsonPath("$.message")
                .value(
                    "Filename '"
                        + filename1_setup
                        + "' already exists for this user (filename conflict during update)."));
  }

  @Test
  void testDeleteFile_success_removesFromDB() throws Exception {
    String filename_setup = "to-be-deleted-" + UUID.randomUUID() + ".txt";
    String userIdForDelete = testUserId + "-deleteSuccess";
    FileResponse uploadedFileResponse =
        uploadHelper(
            userIdForDelete,
            filename_setup,
            "text/plain",
            "delete content",
            Visibility.PUBLIC,
            List.of("delete-me"));
    String fileId_systemUUID = uploadedFileResponse.id();

    mockMvc
        .perform(
            MockMvcRequestBuilders.delete("/api/v1/files/{fileId}", fileId_systemUUID)
                .header("X-User-Id", userIdForDelete)) // Use the owner's ID
        .andExpect(status().isNoContent()); // Expect 204 No Content

    Query fileQuery = Query.query(Criteria.where("filename").is(fileId_systemUUID));
    assertFalse(
        mongoTemplate.exists(fileQuery, "fs.files"),
        "File document should be deleted from fs.files (queried by system UUID in filename field)");
    // Also check FileRecord repository if it's supposed to be in sync
    assertFalse(
        fileRecordRepository.findByFilename(fileId_systemUUID).isPresent(),
        "FileRecord should also be deleted");
  }

  @Test
  void testDeleteFile_unauthorizedUser_returnsForbiddenAndFileRemains() throws Exception {
    String ownerUser = testUserId + "-ownerDelUnauth";
    String attackerUser = testUserId + "-attackerDelUnauth";
    String filename_setup = "owned-by-user1-" + UUID.randomUUID() + ".txt";

    FileResponse uploadedFileResponse =
        uploadHelper(
            ownerUser,
            filename_setup,
            "text/plain",
            "content-owned",
            Visibility.PRIVATE,
            List.of("owned"));
    String fileId_systemUUID = uploadedFileResponse.id();

    mockMvc
        .perform(
            MockMvcRequestBuilders.delete("/api/v1/files/{fileId}", fileId_systemUUID)
                .header("X-User-Id", attackerUser) // Attacker tries to delete
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden()) // Corrected expected status
        .andExpect(
            jsonPath("$.message")
                .value(
                    "User '"
                        + attackerUser
                        + "' not authorized to delete fileId: "
                        + fileId_systemUUID));

    Query fileQuery =
        Query.query(
            Criteria.where("filename").is(fileId_systemUUID).and("metadata.ownerId").is(ownerUser));
    assertTrue(
        mongoTemplate.exists(fileQuery, "fs.files"),
        "File should still exist after unauthorized delete attempt");
  }

  @Test
  void testListFiles_publicAndUserSpecific_withFiltersAndPagination() throws Exception {
    String user1 = "list-user-1";
    String user2 = "list-user-2";

    String user1file1_name = "user1file1-list-" + UUID.randomUUID() + ".txt";
    String user1file2_name = "user1file2-list-" + UUID.randomUUID() + ".pdf";
    String user1file3_name = "user1file3-list-" + UUID.randomUUID() + ".jpg";
    String user2file1_name = "user2file1-list-" + UUID.randomUUID() + ".txt";
    String user2file2_name = "user2file2-list-" + UUID.randomUUID() + ".png";

    uploadHelper(
        user1,
        user1file1_name,
        "text/plain",
        "content1",
        Visibility.PRIVATE,
        List.of("tagA", "common"));
    uploadHelper(
        user1,
        user1file2_name,
        "application/pdf",
        "content2",
        Visibility.PUBLIC,
        List.of("tagB", "common"));
    uploadHelper(
        user1, user1file3_name, "image/jpeg", "content3", Visibility.PRIVATE, List.of("tagA"));

    uploadHelper(
        user2, user2file1_name, "text/plain", "content4", Visibility.PRIVATE, List.of("tagB"));
    uploadHelper(
        user2,
        user2file2_name,
        "image/png",
        "content5",
        Visibility.PUBLIC,
        List.of("tagC", "common"));

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files")
                .param("visibility", "PUBLIC")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[?(@.filename == '" + user1file2_name + "')]").exists())
        .andExpect(jsonPath("$.content[?(@.filename == '" + user2file2_name + "')]").exists());

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files")
                .header("X-User-Id", user1)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3));

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files")
                .header("X-User-Id", user1)
                .param("tag", "tagA")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[?(@.filename == '" + user1file1_name + "')]").exists())
        .andExpect(jsonPath("$.content[?(@.filename == '" + user1file3_name + "')]").exists());

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files")
                .param("visibility", "PUBLIC")
                .param("tag", "CoMmOn")
                .param("sortBy", "filename")
                .param("sortDir", "asc")
                .param("page", "0")
                .param("size", "1")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].filename").exists());

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files")
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
        .andExpect(jsonPath("$.content[0].filename").exists());
  }

  private FileResponse uploadHelper(
      String userId,
      String filename,
      String contentType,
      String content,
      Visibility visibility,
      List<String> tags)
      throws Exception {
    FileUploadRequest request = new FileUploadRequest(filename, visibility, tags);
    // Ensure unique content for each call to prevent unintended SHA256 conflicts during setup
    byte[] uniqueContentBytes = (content + "-" + UUID.randomUUID().toString()).getBytes();
    MockMultipartFile filePart =
        new MockMultipartFile("file", filename, contentType, uniqueContentBytes);
    MockMultipartFile propertiesPart =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(request));

    MvcResult result =
        mockMvc
            .perform(
                MockMvcRequestBuilders.multipart("/api/v1/files")
                    .file(filePart)
                    .file(propertiesPart)
                    .header("X-User-Id", userId)
                    .accept(MediaType.APPLICATION_JSON))
            // Ensure setup uploads are successful for tests that depend on pre-existing files
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), FileResponse.class);
  }

  @Test
  void testUploadFile_parallel_sameFilename_handlesConcurrency() throws Exception {
    String commonFilename = "parallel-filename-test-" + UUID.randomUUID() + ".txt";
    String userId = "parallel-user-1";
    log.info("testUploadFile_parallel_sameFilename: commonFilename for test: {}", commonFilename);
    int numberOfThreads = 3;
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newFixedThreadPool(numberOfThreads);
    java.util.List<java.util.concurrent.Future<MvcResult>> results = new java.util.ArrayList<>();
    java.util.concurrent.atomic.AtomicInteger successCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.concurrent.atomic.AtomicInteger conflictCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.concurrent.atomic.AtomicInteger otherErrorCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.concurrent.atomic.AtomicInteger conflictResponses =
        new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.List<MvcResult> conflictResponsesList =
        Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < numberOfThreads; i++) {
      int taskNumber = i;
      String currentOriginalFilenameInLoop =
          commonFilename; // All threads use the same original filename request
      java.util.concurrent.Callable<MvcResult> task =
          () -> {
            log.info(
                "[Thread {}] Parallel filename test: Attempting upload with originalFilename: {}",
                taskNumber,
                currentOriginalFilenameInLoop);
            FileUploadRequest request =
                new FileUploadRequest(
                    currentOriginalFilenameInLoop,
                    Visibility.PRIVATE,
                    List.of("parallel", "task" + taskNumber));
            MockMultipartFile filePart =
                new MockMultipartFile(
                    "file",
                    "original" + taskNumber + ".txt",
                    "text/plain",
                    ("content" + taskNumber).getBytes());
            MockMultipartFile propertiesPart =
                new MockMultipartFile(
                    "properties",
                    null,
                    MediaType.APPLICATION_JSON_VALUE,
                    objectMapper.writeValueAsBytes(request));

            return mockMvc
                .perform(
                    MockMvcRequestBuilders.multipart("/api/v1/files")
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
      } else if (result.getResponse().getStatus()
          == org.springframework.http.HttpStatus.CONFLICT.value()) {
        conflictCount.incrementAndGet();
        conflictResponses.incrementAndGet();
        conflictResponsesList.add(result);
      } else {
        otherErrorCount.incrementAndGet();
        System.err.println(
            "Parallel test got unexpected status: "
                + result.getResponse().getStatus()
                + " with body: "
                + result.getResponse().getContentAsString());
      }
    }
    executor.shutdown();
    assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

    assertEquals(
        1, successCount.get(), "Exactly one upload should succeed for parallel filename attempts.");
    assertEquals(
        numberOfThreads - 1,
        conflictCount.get(),
        "The other parallel filename attempts should result in conflict.");
    assertEquals(
        0, otherErrorCount.get(), "There should be no other errors in parallel filename attempts.");

    Query filesQuery =
        Query.query(
            Criteria.where("metadata.originalFilename")
                .is(commonFilename)
                .and("metadata.ownerId")
                .is(userId));
    long dbCount = mongoTemplate.count(filesQuery, "fs.files");
    assertEquals(
        1,
        dbCount,
        "Only one file with that originalFilename should be in the DB for the user eventually.");

    // Check the error message for one of the conflict responses
    assertTrue(
        conflictResponsesList.stream()
            .anyMatch(
                response -> {
                  try {
                    String body = response.getResponse().getContentAsString();
                    return body.contains(
                        "Filename '" + commonFilename + "' already exists for this user.");
                  } catch (UnsupportedEncodingException e) {
                    return false;
                  }
                }),
        "Conflict message for filename should contain the correct text.");
  }

  @Test
  void testUploadFile_parallel_sameContent_handlesConcurrency() throws Exception {
    String userId = "parallel-user-2";
    byte[] commonContent = "identical-parallel-content".getBytes();
    int numberOfThreads = 3;
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newFixedThreadPool(numberOfThreads);
    java.util.List<java.util.concurrent.Future<MvcResult>> results = new java.util.ArrayList<>();
    java.util.concurrent.atomic.AtomicInteger successCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.concurrent.atomic.AtomicInteger conflictCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.concurrent.atomic.AtomicInteger otherErrorCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.concurrent.atomic.AtomicInteger conflictResponses =
        new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.List<MvcResult> conflictResponsesList =
        Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < numberOfThreads; i++) {
      String uniqueFilenameInLoop = "parallel-content-file-" + i + "-" + UUID.randomUUID() + ".txt";
      int taskNumber = i;
      java.util.concurrent.Callable<MvcResult> task =
          () -> {
            log.info(
                "[Thread {}] Parallel content test: Attempting upload with unique originalFilename: {}",
                taskNumber,
                uniqueFilenameInLoop);
            FileUploadRequest request =
                new FileUploadRequest(
                    uniqueFilenameInLoop, Visibility.PRIVATE, List.of("parallel-content"));
            MockMultipartFile filePart =
                new MockMultipartFile(
                    "file", "original-" + uniqueFilenameInLoop, "text/plain", commonContent);
            MockMultipartFile propertiesPart =
                new MockMultipartFile(
                    "properties",
                    null,
                    MediaType.APPLICATION_JSON_VALUE,
                    objectMapper.writeValueAsBytes(request));

            return mockMvc
                .perform(
                    MockMvcRequestBuilders.multipart("/api/v1/files")
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
      } else if (result.getResponse().getStatus()
          == org.springframework.http.HttpStatus.CONFLICT.value()) {
        conflictCount.incrementAndGet();
        conflictResponses.incrementAndGet();
        conflictResponsesList.add(result);
      } else {
        otherErrorCount.incrementAndGet();
      }
    }
    executor.shutdown();
    assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

    assertEquals(
        1, successCount.get(), "Exactly one upload should succeed for parallel content attempts.");
    assertEquals(
        numberOfThreads - 1,
        conflictCount.get(),
        "The other parallel content attempts should result in conflict.");
    assertEquals(
        0, otherErrorCount.get(), "There should be no other errors in parallel content attempts.");

    if (!conflictResponsesList.isEmpty()) {
      String firstConflictResponse =
          conflictResponsesList.get(0).getResponse().getContentAsString();
      assertTrue(
          firstConflictResponse.contains("Content with hash")
              && firstConflictResponse.contains("already exists for this user."),
          "Conflict message for content should contain the correct text elements.");
    } else if (conflictCount.get() < numberOfThreads) {
      fail(
          "No uploads succeeded, but not all conflicted due to content. Success: "
              + successCount.get()
              + ", Conflict: "
              + conflictCount.get());
    }
  }

  @Test
  void testIndexRecreationAfterCollectionDrop() {
    log.info("--- testIndexRecreationAfterCollectionDrop: START ---");
    // Drop the fs.files collection
    mongoTemplate.getDb().getCollection("fs.files").drop();
    log.info("Dropped fs.files collection for index recreation test.");

    // Call the utility to synchronously recreate all indexes for FileRecord
    recreateFileRecordIndexes();

    // Now assert that all required indexes are present
    MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext =
        mongoTemplate.getConverter().getMappingContext();
    MongoPersistentEntityIndexResolver resolver =
        new MongoPersistentEntityIndexResolver(mappingContext);
    List<String> expectedIndexNames =
        StreamSupport.stream(
                resolver
                    .resolveIndexFor(com.example.storage_app.model.FileRecord.class)
                    .spliterator(),
                false)
            .map(index -> index.getIndexOptions().getString("name"))
            .toList();
    List<String> actualIndexNames =
        mongoTemplate
            .getDb()
            .getCollection("fs.files")
            .listIndexes()
            .map(doc -> doc.getString("name"))
            .into(new java.util.ArrayList<>());
    for (String expected : expectedIndexNames) {
      assertTrue(
          actualIndexNames.contains(expected),
          "Expected index '" + expected + "' to be present after recreation, but it was missing.");
    }
    log.info("--- testIndexRecreationAfterCollectionDrop: END ---");
  }

  // Utility method to be implemented for index recreation
  private void recreateFileRecordIndexes() {
    MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext =
        mongoTemplate.getConverter().getMappingContext();
    MongoPersistentEntityIndexResolver resolver =
        new MongoPersistentEntityIndexResolver(mappingContext);
    resolver
        .resolveIndexFor(com.example.storage_app.model.FileRecord.class)
        .forEach(
            index ->
                mongoTemplate
                    .indexOps(com.example.storage_app.model.FileRecord.class)
                    .ensureIndex(index));
  }
}
