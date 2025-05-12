package com.example.storage_app.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.model.FileRecord;
import com.example.storage_app.model.Visibility;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileMapperTest {

  @InjectMocks private FileMapper fileMapper;

  private String testSystemFileId;
  private String testOriginalFilename;
  private Visibility testVisibility;
  private List<String> testTags;
  private Date testUploadDate;
  private String testContentType;
  private long testSize;
  private String testToken;
  private String testDownloadLinkBase = "/api/v1/files/download/";

  @BeforeEach
  void setUp() {
    testSystemFileId = UUID.randomUUID().toString();
    testOriginalFilename = "test_original_file.txt";
    testVisibility = Visibility.PUBLIC;
    testTags = List.of("tag1", "tag2");
    testUploadDate = new Date();
    testContentType = "text/plain";
    testSize = 12345L;
    testToken = UUID.randomUUID().toString();
  }

  @Test
  void fromEntity_validFileRecord_mapsCorrectly() {
    FileRecord record =
        FileRecord.builder()
            .id(testSystemFileId)
            .filename(testSystemFileId) // Assuming system ID is used as filename in record
            .originalFilename(testOriginalFilename)
            .visibility(testVisibility)
            .tags(testTags)
            .uploadDate(testUploadDate)
            .contentType(testContentType)
            .size(testSize)
            .token(testToken)
            .build();

    FileResponse response = fileMapper.fromEntity(record);

    assertNotNull(response);
    assertEquals(testSystemFileId, response.id());
    assertEquals(testOriginalFilename, response.filename());
    assertEquals(testVisibility, response.visibility());
    assertEquals(testTags, response.tags());
    assertEquals(testUploadDate, response.uploadDate());
    assertEquals(testContentType, response.contentType());
    assertEquals(testSize, response.size());
    assertEquals(testDownloadLinkBase + testToken, response.downloadLink());
  }

  @Test
  void fromEntity_nullFileRecord_returnsNull() {
    FileResponse response = fileMapper.fromEntity(null);
    assertNull(response);
  }

  @Test
  void toResponse_validFileStorageResult_mapsCorrectly() {
    Document metadata =
        new Document()
            .append("systemFilenameUUID", testSystemFileId)
            .append("originalFilename", testOriginalFilename)
            .append("visibility", testVisibility.toString())
            .append("tags", testTags)
            .append("uploadDate", testUploadDate)
            // contentType and size come from FileStorageResult directly
            .append("token", testToken);

    FileStorageResult result =
        new FileStorageResult(
            new ObjectId(), // gridFsId, not directly in FileResponse
            "someSha256", // sha256, not directly in FileResponse
            testContentType,
            testSize,
            metadata);

    FileResponse response = fileMapper.toResponse(result);

    assertNotNull(response);
    assertEquals(testSystemFileId, response.id());
    assertEquals(testOriginalFilename, response.filename());
    assertEquals(testVisibility, response.visibility());
    assertEquals(testTags, response.tags());
    assertEquals(testUploadDate, response.uploadDate());
    assertEquals(testContentType, response.contentType());
    assertEquals(testSize, response.size());
    assertEquals(testDownloadLinkBase + testToken, response.downloadLink());
  }

  @Test
  void fromDocument_fullMetadata_mapsCorrectly() {
    Document metadata =
        new Document()
            .append("originalFilename", testOriginalFilename)
            .append("visibility", testVisibility.toString())
            .append("tags", testTags)
            .append("contentType", testContentType)
            .append("size", testSize)
            .append("token", testToken);

    Document fsFileDoc =
        new Document()
            .append("filename", testSystemFileId) // This is the system UUID
            .append("uploadDate", testUploadDate)
            .append("metadata", metadata);

    FileResponse response = fileMapper.fromDocument(fsFileDoc);

    assertNotNull(response);
    assertEquals(testSystemFileId, response.id());
    assertEquals(testOriginalFilename, response.filename());
    assertEquals(testVisibility, response.visibility());
    assertEquals(testTags, response.tags());
    assertEquals(testUploadDate, response.uploadDate());
    assertEquals(testContentType, response.contentType());
    assertEquals(testSize, response.size());
    assertEquals(testDownloadLinkBase + testToken, response.downloadLink());
  }

  @Test
  void fromDocument_missingMetadata_throwsRuntimeException() {
    Document fsFileDoc =
        new Document().append("filename", testSystemFileId).append("uploadDate", testUploadDate);
    // metadata is missing

    Exception exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              fileMapper.fromDocument(fsFileDoc);
            });
    assertEquals(
        "File metadata is missing for system ID: " + testSystemFileId, exception.getMessage());
  }

  @Test
  void fromDocument_nullVisibility_defaultsToPrivate() {
    Document metadata =
        new Document()
            .append("originalFilename", testOriginalFilename)
            .append("visibility", (String) null) // Explicitly null visibility
            .append("token", testToken);
    Document fsFileDoc =
        new Document().append("filename", testSystemFileId).append("metadata", metadata);

    FileResponse response = fileMapper.fromDocument(fsFileDoc);
    assertEquals(Visibility.PRIVATE, response.visibility());
  }

  @Test
  void fromDocument_invalidVisibilityString_defaultsToPrivate() {
    Document metadata =
        new Document()
            .append("originalFilename", testOriginalFilename)
            .append("visibility", "INVALID_VISIBILITY_STRING")
            .append("token", testToken);
    Document fsFileDoc =
        new Document().append("filename", testSystemFileId).append("metadata", metadata);

    FileResponse response = fileMapper.fromDocument(fsFileDoc);
    assertEquals(Visibility.PRIVATE, response.visibility());
  }

  @Test
  void fromDocument_emptyVisibilityString_defaultsToPrivate() {
    Document metadata =
        new Document()
            .append("originalFilename", testOriginalFilename)
            .append("visibility", "") // Empty visibility string
            .append("token", testToken);
    Document fsFileDoc =
        new Document().append("filename", testSystemFileId).append("metadata", metadata);

    FileResponse response = fileMapper.fromDocument(fsFileDoc);
    assertEquals(Visibility.PRIVATE, response.visibility());
  }

  @Test
  void fromDocument_lowercaseVisibilityString_mapsCorrectly() {
    Document metadata =
        new Document()
            .append("originalFilename", testOriginalFilename)
            .append("visibility", "public") // lowercase
            .append("token", testToken);
    Document fsFileDoc =
        new Document().append("filename", testSystemFileId).append("metadata", metadata);

    FileResponse response = fileMapper.fromDocument(fsFileDoc);
    assertEquals(Visibility.PUBLIC, response.visibility());
  }

  @Test
  void fromDocument_nullTags_defaultsToEmptyList() {
    Document metadata =
        new Document()
            .append("originalFilename", testOriginalFilename)
            // tags field is missing
            .append("token", testToken);
    Document fsFileDoc =
        new Document().append("filename", testSystemFileId).append("metadata", metadata);

    FileResponse response = fileMapper.fromDocument(fsFileDoc);
    assertTrue(response.tags().isEmpty());
  }

  @Test
  void fromDocument_nullSize_defaultsToZero() {
    Document metadata =
        new Document()
            .append("originalFilename", testOriginalFilename)
            .append("size", (Long) null) // Explicitly null size
            .append("token", testToken);
    Document fsFileDoc =
        new Document().append("filename", testSystemFileId).append("metadata", metadata);

    FileResponse response = fileMapper.fromDocument(fsFileDoc);
    assertEquals(0L, response.size());
  }

  @Test
  void fromDocument_minimalMetadata_mapsWithDefaults() {
    // Only provide systemFileId (filename in fsFileDoc) and a minimal metadata doc with a token
    Document metadata = new Document().append("token", testToken);
    Document fsFileDoc =
        new Document().append("filename", testSystemFileId).append("metadata", metadata);

    FileResponse response = fileMapper.fromDocument(fsFileDoc);
    assertNotNull(response);
    assertEquals(testSystemFileId, response.id());
    assertNull(response.filename()); // originalFilename defaults to null
    assertEquals(Visibility.PRIVATE, response.visibility()); // Defaults to private
    assertTrue(response.tags().isEmpty()); // Defaults to empty list
    assertNull(response.uploadDate()); // Defaults to null
    assertNull(response.contentType()); // Defaults to null
    assertEquals(0L, response.size()); // Defaults to 0L
    assertEquals(testDownloadLinkBase + testToken, response.downloadLink());
  }
}
