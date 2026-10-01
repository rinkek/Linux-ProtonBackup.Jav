package protonbackup.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One entry of {@code filesystem list --json}. A name is a result type because decrypting it can
 * fail ({@code ok=false}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteNode(String uid, String type, RemoteValue<String> name, RemoteRevision activeRevision) {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    public RemoteNode {
        uid = uid == null ? "" : uid;
        type = type == null ? "" : type;
    }

    public boolean isFolder() {
        return "folder".equals(type);
    }

    /** The decrypted name, or {@code null} when it could not be decrypted. */
    public String fileName() {
        return name != null && name.ok() ? name.value() : null;
    }

    /** Parses the CLI's JSON array; blank input is an empty list. Throws on malformed JSON. */
    public static List<RemoteNode> parseList(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) return List.of();
        List<RemoteNode> nodes = MAPPER.readValue(json, new TypeReference<List<RemoteNode>>() {});
        return nodes == null ? List.of() : nodes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteValue<T>(boolean ok, T value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteRevision(Long claimedSize, String claimedModificationTime) {

        /** The claimed modification time, or {@code null} when the CLI did not report one. */
        public OffsetDateTime claimedModificationInstant() {
            return claimedModificationTime == null ? null : OffsetDateTime.parse(claimedModificationTime);
        }
    }
}
