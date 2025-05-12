package com.example.storage_app.model;

import java.util.Date;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document("fs.files")
@CompoundIndexes({
  @CompoundIndex(
      name = "owner_filename_idx",
      def = "{'metadata.ownerId': 1, 'metadata.originalFilename': 1}",
      unique = true),
  @CompoundIndex(
      name = "owner_sha256_idx",
      def = "{'metadata.ownerId': 1, 'metadata.sha256': 1}",
      unique = true,
      sparse = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileRecord {
  @Id private String id;

  @Indexed private String filename;

  @Field("length")
  @Indexed
  private long size;

  @Field("metadata.originalFilename")
  private String originalFilename;

  @Field("metadata.uploadDate")
  @Indexed
  private Date uploadDate;

  @Field("metadata.contentType")
  @Indexed
  private String contentType;

  @Field("metadata.ownerId")
  private String ownerId;

  @Field("metadata.visibility")
  @Indexed
  private Visibility visibility;

  @Field("metadata.tags")
  @Indexed
  private List<String> tags;

  @Field("metadata.sha256")
  private String sha256;

  @Field("metadata.token")
  @Indexed(unique = true, name = "download_token_idx")
  private String token;
}
