package com.example.storage_app;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.FileBody;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.entity.mime.StringBody;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class FileStorageHttpIntegrationTest {

  @Container static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:8.0");

  @DynamicPropertySource
  static void setProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
  }

  @LocalServerPort int port;

  @Test
  void uploadFile_withApacheHttpClient_shouldSucceed() throws Exception {
    String url = "http://localhost:" + port + "/api/v1/files";
    String userId = "http-int-test-user";
    String filename = "httpclient-upload-" + System.currentTimeMillis() + ".txt";
    String tag = "httpclient";
    String visibility = "PRIVATE";
    String fileContent = "This is a test file uploaded via Apache HttpClient. " + UUID.randomUUID();

    // Create a temp file for upload
    File tempFile = File.createTempFile("upload-test", ".txt");
    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
      fos.write(fileContent.getBytes(StandardCharsets.UTF_8));
    }

    // Build the multipart request
    String propertiesJson =
        String.format(
            "{\"filename\":\"%s\",\"visibility\":\"%s\",\"tags\":[\"%s\"]}",
            filename, visibility, tag);

    HttpPost post = new HttpPost(url);
    post.addHeader("X-User-Id", userId);
    MultipartEntityBuilder builder = MultipartEntityBuilder.create();
    builder.setMode(HttpMultipartMode.STRICT);
    builder.addPart("file", new FileBody(tempFile, ContentType.TEXT_PLAIN, filename));
    builder.addPart("properties", new StringBody(propertiesJson, ContentType.APPLICATION_JSON));
    HttpEntity multipart = builder.build();
    post.setEntity(multipart);

    try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
      var response = client.execute(post);
      int status = response.getCode();
      String respBody = EntityUtils.toString(response.getEntity());
      assertEquals(201, status, "Expected 201 Created, got: " + status + "\n" + respBody);
      // Parse and check JSON response
      ObjectMapper om = new ObjectMapper();
      JsonNode json = om.readTree(respBody);
      assertNotNull(json.get("id"));
      assertEquals(filename, json.get("filename").asText());
      assertEquals(visibility, json.get("visibility").asText());
      assertTrue(json.get("tags").isArray());
      assertEquals(tag, json.get("tags").get(0).asText());
      assertEquals(fileContent.length(), json.get("size").asInt());
      assertNotNull(json.get("downloadLink"));
    } finally {
      tempFile.delete();
    }
  }

  @Test
  @Disabled(
      "Very large file test; enable only for manual runs. This test creates and uploads a file >2GiB.")
  void uploadFile_over2GiB_withApacheHttpClient_shouldSucceed() throws Exception {
    String url = "http://localhost:" + port + "/api/v1/files";
    String userId = "http-int-test-user";
    String filename = "httpclient-upload-2gib-" + System.currentTimeMillis() + ".bin";
    String tag = "hugefile";
    String visibility = "PRIVATE";
    long fileSize = (2L * 1024 * 1024 * 1024) + 1; // 2 GiB + 1 byte
    String uniqueMarker = UUID.randomUUID().toString();

    // Create a sparse file (efficient on most modern filesystems)
    File tempFile = File.createTempFile("upload-2gib-test", ".bin");
    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
      fos.getChannel().position(fileSize - uniqueMarker.length() - 1);
      fos.write(uniqueMarker.getBytes(StandardCharsets.UTF_8));
      fos.write(0); // Write a single byte at the end
    }

    String propertiesJson =
        String.format(
            "{\"filename\":\"%s\",\"visibility\":\"%s\",\"tags\":[\"%s\"]}",
            filename, visibility, tag);

    HttpPost post = new HttpPost(url);
    post.addHeader("X-User-Id", userId);
    MultipartEntityBuilder builder = MultipartEntityBuilder.create();
    builder.setMode(HttpMultipartMode.STRICT);
    builder.addPart("file", new FileBody(tempFile, ContentType.APPLICATION_OCTET_STREAM, filename));
    builder.addPart("properties", new StringBody(propertiesJson, ContentType.APPLICATION_JSON));
    HttpEntity multipart = builder.build();
    post.setEntity(multipart);

    try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
      var response = client.execute(post);
      int status = response.getCode();
      String respBody = EntityUtils.toString(response.getEntity());
      assertEquals(201, status, "Expected 201 Created, got: " + status + "\n" + respBody);
      ObjectMapper om = new ObjectMapper();
      JsonNode json = om.readTree(respBody);
      assertNotNull(json.get("id"));
      assertEquals(filename, json.get("filename").asText());
      assertEquals(visibility, json.get("visibility").asText());
      assertTrue(json.get("tags").isArray());
      assertEquals(tag, json.get("tags").get(0).asText());
      assertEquals(fileSize, json.get("size").asLong());
      assertNotNull(json.get("downloadLink"));
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void deleteFile_afterFilenameUpdate_shouldSucceed() throws Exception {
    String baseUrl = "http://localhost:" + port + "/api/v1/files";
    String userId = "delete-after-update-user";
    String initialFilename = "initial-delete-test-" + System.currentTimeMillis() + ".txt";
    String updatedFilename = "updated-delete-test-" + System.currentTimeMillis() + ".txt";
    String tag = "delete-test";
    String visibility = "PRIVATE";
    String fileContent = "Test content for delete after update. " + UUID.randomUUID();

    File tempFile = File.createTempFile("delete-update-test", ".txt");
    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
      fos.write(fileContent.getBytes(StandardCharsets.UTF_8));
    }

    String fileId;
    ObjectMapper objectMapper = new ObjectMapper();

    try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
      HttpPost post = new HttpPost(baseUrl);
      post.addHeader("X-User-Id", userId);
      String propertiesJson =
          String.format(
              "{\"filename\":\"%s\",\"visibility\":\"%s\",\"tags\":[\"%s\"]}",
              initialFilename, visibility, tag);
      MultipartEntityBuilder builder = MultipartEntityBuilder.create();
      builder.addPart("file", new FileBody(tempFile, ContentType.TEXT_PLAIN, initialFilename));
      builder.addPart("properties", new StringBody(propertiesJson, ContentType.APPLICATION_JSON));
      post.setEntity(builder.build());

      var response = client.execute(post);
      int status = response.getCode();
      String respBody = EntityUtils.toString(response.getEntity());
      assertEquals(201, status, "Upload failed: " + status + "\n" + respBody);
      JsonNode json = objectMapper.readTree(respBody);
      fileId = json.get("id").asText();
      assertNotNull(fileId, "File ID was null after upload");
    }

    try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
      HttpPatch patch = new HttpPatch(baseUrl + "/" + fileId);
      patch.addHeader("X-User-Id", userId);
      patch.addHeader("Content-Type", "application/json");
      String updateJson = String.format("{\"newFilename\":\"%s\"}", updatedFilename);
      patch.setEntity(new StringEntity(updateJson, ContentType.APPLICATION_JSON));

      var response = client.execute(patch);
      int status = response.getCode();
      String respBody = EntityUtils.toString(response.getEntity());
      assertEquals(200, status, "Update failed: " + status + "\n" + respBody);
      JsonNode json = objectMapper.readTree(respBody);
      assertEquals(
          updatedFilename, json.get("filename").asText(), "Filename not updated correctly");
    }

    try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
      HttpDelete delete = new HttpDelete(baseUrl + "/" + fileId);
      delete.addHeader("X-User-Id", userId);

      var response = client.execute(delete);
      int status = response.getCode();
      EntityUtils.consume(response.getEntity());
      assertEquals(204, status, "Delete failed: Expected 204 No Content, got " + status);
    }

    try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
      HttpGet get = new HttpGet(baseUrl + "?page=0&size=1");
      get.addHeader("X-User-Id", userId);

      var response = client.execute(get);
      int status = response.getCode();
      String respBody = EntityUtils.toString(response.getEntity());

      assertEquals(200, status, "List files request failed: " + status);
      JsonNode json = objectMapper.readTree(respBody);
      assertEquals(0, json.get("totalElements").asInt(), "File list should be empty after delete");

    } finally {
      tempFile.delete();
    }
  }
}
