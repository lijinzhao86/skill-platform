package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractIT;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T6 in test-plan.md, and §4.2's L1 contract.
 *
 * <p>The load-bearing assertion is the 404: an unreadable private skill must be indistinguishable
 * from one that does not exist. Everything else here is shape.
 *
 * <p>The skill is inserted directly rather than published, because what is under test is the read
 * path. {@code SkillPublishIT} owns the write path, and going through it would make a failure here
 * ambiguous between the two.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillDetailIT extends AbstractIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
    private static final String OTHER_NAMESPACE_ID = "01M3HTGC79CHKDB4Q0T2JMRCWV";
    private static final String OTHER_USER_ID = "01M3HTG7GDQ71Q28CCP7J0HM8T";

    /** Deliberately carries a nested mapping and a key nothing in the codebase knows about. */
    private static final String FRONTMATTER = """
            {"name":"pdf-tools","description":"Extract and merge PDFs",
             "metadata":{"platform_api_version":"1","vendor":{"name":"Acme"}},
             "x-unknown-field":"kept"}""";

    private static final String BODY = "the SKILL.md bytes";
    private static final String CHECKLIST = "checklist body";

    @Test
    void returnsTheWholeManifestAndNoContent() {
        String id = insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools");

        HttpResponse<String> response = get("/v1/skills/" + id, token());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "name", "title",
                "description", "namespace", "visibility", "frontmatter", "version", "files",
                "resources");
        assertThat(body.get("id").asText()).isEqualTo(id);
        assertThat(body.get("name").asText()).isEqualTo("pdf-tools");
        assertThat(body.get("namespace").get("slug").asText()).isEqualTo("demo");
        assertThat(body.get("namespace").get("title").asText()).isEqualTo("Demo owner");
        assertThat(body.get("visibility").asText()).isEqualTo("private");
        assertThat(body.get("version").propertyNames()).containsExactlyInAnyOrder(
                "digest", "published_at", "file_count", "total_bytes");
        assertThat(body.get("version").get("digest").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(body.get("version").get("file_count").asInt()).isEqualTo(2);
        assertThat(body.get("version").get("total_bytes").asLong())
                .isEqualTo(BODY.length() + CHECKLIST.length());
        assertThat(body.get("resources").get("body").asText()).isEqualTo("/v1/skills/{id}/body");
        assertThat(body.get("resources").get("file").asText())
                .isEqualTo("/v1/skills/{id}/files/{relpath}");
    }

    @Test
    void listsFilesInManifestOrderAndCarriesNoneOfTheirContent() {
        // Order comes from the Manifest record, not from the query: the digest and this list have
        // to agree, and PostgreSQL's collation is one of the orders that would disagree. The
        // fixture inserts them in the opposite order, so a query relying on heap order shows up.
        String id = insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools");

        HttpResponse<String> response = get("/v1/skills/" + id, token());
        JsonNode files = JSON.readTree(response.body()).get("files");

        assertThat(files).hasSize(2);
        assertThat(files.get(0).get("relpath").asText()).isEqualTo("SKILL.md");
        assertThat(files.get(1).get("relpath").asText()).isEqualTo("references/checklist.md");
        assertThat(files.get(0).propertyNames())
                .containsExactlyInAnyOrder("relpath", "sha256", "size", "is_binary");
        assertThat(files.get(0).get("sha256").asText()).matches("sha256:[0-9a-f]{64}");

        // "No content" is the whole point of the endpoint: this manifest is what lets an agent
        // decide whether to fetch anything, and it cannot do that if the answer already holds the
        // bytes. The description is present on purpose — it is L1 metadata — so only file content
        // is checked for absence.
        assertThat(response.body())
                .doesNotContain(BODY)
                .doesNotContain(CHECKLIST);
    }

    @Test
    void passesFrontmatterThroughIncludingNestedAndUnknownFields() {
        // §3.3: unknown fields are stored and returned, which is what the archived baseline got
        // wrong. A nested mapping is the shape most likely to be flattened by an implementation
        // that "helpfully" understood the schema.
        String id = insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools");

        JsonNode frontmatter = JSON.readTree(get("/v1/skills/" + id, token()).body())
                .get("frontmatter");

        assertThat(frontmatter.isObject())
                .as("a JSON object, not a string containing JSON")
                .isTrue();
        assertThat(frontmatter.get("metadata").get("platform_api_version").asText()).isEqualTo("1");
        assertThat(frontmatter.get("metadata").get("vendor").get("name").asText()).isEqualTo("Acme");
        assertThat(frontmatter.get("x-unknown-field").asText()).isEqualTo("kept");
    }

    @Test
    void anotherUsersSkillIsNotFoundRatherThanForbidden() {
        String id = insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "not-mine");

        HttpResponse<String> response = get("/v1/skills/" + id, token());

        assertThat(response.statusCode())
                .as("a 403 would confirm the skill exists; §4.2 requires 404")
                .isEqualTo(404);
        assertThat(response.body())
                .as("and the response must not leak the name either")
                .doesNotContain("not-mine");
    }

    @Test
    void anUnknownIdIsIndistinguishableFromAnotherUsersId() {
        HttpResponse<String> unknown = get("/v1/skills/" + Ulid.generate(), token());
        HttpResponse<String> someones = get(
                "/v1/skills/" + insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "not-mine"), token());

        assertThat(unknown.statusCode()).isEqualTo(someones.statusCode());
        assertThat(unknown.body()).isEqualTo(someones.body());
    }

    @Test
    void aDeletedSkillIsNotFound() {
        String id = insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools");
        jdbc.sql("UPDATE skill SET deleted_at = :at WHERE id = :id")
                .param("at", Timestamps.now()).param("id", id).update();

        assertThat(get("/v1/skills/" + id, token()).statusCode()).isEqualTo(404);
    }

    @Test
    void anIdThatIsNotAnIdIsNotFoundRatherThanAnError() {
        // No ULID validation on the path: a malformed id matches no row, which is already the right
        // answer. Rejecting it earlier would turn a 404 into a 400 telling the caller their id was
        // *shaped* wrong — a distinction the API otherwise never makes.
        assertThat(get("/v1/skills/not-a-ulid", token()).statusCode()).isEqualTo(404);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Inserts a skill with two files, one of them under {@code references/}.
     *
     * <p>Written out rather than published through the API so that a failure here cannot be a
     * failure of the publish path. Sizes and digests are the real ones: a fixture that lies about
     * them would hide a formatting bug in the response.
     */
    private String insertSkill(String namespaceId, String userId, String name) {
        String skillId = Ulid.generate();
        String versionId = Ulid.generate();
        String at = "2026-09-28T00:00:00Z";

        Blob body = insertBlob(BODY);
        Blob checklist = insertBlob(CHECKLIST);

        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, current_version_id, created_by, created_at, updated_at)
                VALUES (:id, :namespace, :name, 'PDF tools', 'Extract and merge PDFs', :frontmatter,
                        'private', :version, :user, :at, :at)
                """)
                .param("id", skillId).param("namespace", namespaceId).param("name", name)
                .param("frontmatter", FRONTMATTER).param("version", versionId)
                .param("user", userId).param("at", at)
                .update();

        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, digest, file_count, total_bytes,
                                           changelog, source, published_by, published_at)
                VALUES (:id, :skill, :digest, 2, :total, '', 'zip', :user, :at)
                """)
                .param("id", versionId).param("skill", skillId)
                // A real digest of the name: 64 lowercase hex characters, as the read path expects
                // to present. Nothing on this path recomputes it, so it only has to be well-formed.
                .param("digest", sha256Hex(name.getBytes(StandardCharsets.UTF_8)))
                .param("total", body.size() + checklist.size())
                .param("user", userId).param("at", at)
                .update();

        // Inserted in the reverse of manifest order on purpose — see the ordering test.
        insertFile(versionId, "references/checklist.md", checklist);
        insertFile(versionId, "SKILL.md", body);
        return skillId;
    }

    private Blob insertBlob(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(bytes);
        jdbc.sql("INSERT INTO blob (sha256, size, created_at) VALUES (:sha, :size, :at)")
                .param("sha", sha).param("size", bytes.length).param("at", Timestamps.now())
                .update();
        jdbc.sql("INSERT INTO blob_content (sha256, bytes) VALUES (:sha, :bytes)")
                .param("sha", sha).param("bytes", bytes)
                .update();
        return new Blob(sha, bytes.length);
    }

    private void insertFile(String versionId, String relpath, Blob blob) {
        jdbc.sql("""
                INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary)
                VALUES (:version, :relpath, :sha, :size, 0)
                """)
                .param("version", versionId).param("relpath", relpath)
                .param("sha", blob.sha()).param("size", blob.size())
                .update();
    }

    private record Blob(String sha, long size) {
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }
}
