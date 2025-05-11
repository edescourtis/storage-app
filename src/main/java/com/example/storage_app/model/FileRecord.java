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
      def = "{'metadata.ownerId': 1, 'filename': 1}",
      unique = true),
  @CompoundIndex(
      name = "owner_sha256_idx",
      def = "{'metadata.ownerId': 1, 'metadata.sha256': 1}",
      unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileRecord {
  @Id private String id;

  @Indexed private String filename;

  @Indexed private Date uploadDate;

  @Indexed private String contentType;

  @Field("length")
  @Indexed
  private long size;

  private String ownerId;
  private Visibility visibility;
  private List<String> tags;
  private String sha256;
  private String originalFilename;

  @Indexed(unique = true, name = "download_token_idx")
  private String token;
}
