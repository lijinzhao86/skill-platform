package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The publish path end to end: M5 validates, M6 stores, M7 records, M10 trails, and the whole
 * thing commits or rolls back as one transaction.
 *
 * <p>The rejections M5 owns — 513 files, 16 MiB, traversal, symlinks — are proven in
 * {@code SkillUploadValidatorTest}, where they need no server. Two of them are repeated here for a
 * different reason: what is under test over HTTP is the exception-to-envelope mapping and the
 * multipart ceiling, neither of which exists below the API. Repeating all of them would re-test M5
 * at a higher cost.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillPublishIT extends AbstractIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** From V2__seed_owner_and_namespaces.sql: the namespace and user the token does not act as. */
    private static final String OTHER_NAMESPACE_ID = "01M3HTGC79CHKDB4Q0T2JMRCWV";
    private static final String OTHER_USER_ID = "01M3HTG7GDQ71Q28CCP7J0HM8T";

    @Test
    void publishesAZipAndReturnsTheCreatedVersion() {
        byte[] zip = skill("pdf-tools", Map.of());

        HttpResponse<String> response = publish(zip);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());

        // Key names are asserted, not just values: §4.2's wire format is snake_case, and it is set
        // globally on the ObjectMapper rather than annotated field by field — so a field whose name
        // silently changed shape would otherwise go unnoticed.
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "id", "name", "namespace", "created", "version");
        assertThat(body.get("version").propertyNames()).containsExactlyInAnyOrder(
                "digest", "file_count", "total_bytes", "published_at");

        assertThat(Ulid.isValid(body.get("id").asText())).isTrue();
        assertThat(body.get("name").asText()).isEqualTo("pdf-tools");
        assertThat(body.get("namespace").asText()).isEqualTo("demo");
        assertThat(body.get("created").asBoolean()).isTrue();

        assertThat(body.get("version").get("digest").asText())
                // The prefix plus 64 hex characters: the API presents the prefix, storage keeps the
                // bare hex that ADR 0005's formula produces.
                .hasSize("sha256:".length() + 64)
                .matches("sha256:[0-9a-f]{64}");
        assertThat(body.get("version").get("file_count").asInt()).isEqualTo(1);
        assertThat(body.get("version").get("total_bytes").asLong())
                .isEqualTo(skillMd("pdf-tools").getBytes(StandardCharsets.UTF_8).length);
        assertThat(body.get("version").get("published_at").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    @Test
    void publishingLeavesAnAuditTrail() {
        // §3.5: content is taken away and used in client environments, so attribution afterwards is
        // the floor. The row is written inside the publish transaction, which is what makes it
        // evidence rather than a note.
        String id = publishAndReadId(skill("pdf-tools", Map.of()));

        assertThat(count("""
                SELECT count(*) FROM audit_event
                WHERE action = 'publish' AND target_type = 'skill' AND target_id = :id
                  AND actor_user_id = :actor
                """, Map.of("id", id, "actor", properties.auth().subjectUserId())))
                .isEqualTo(1);
    }

    @Test
    void republishingIdenticalContentIsIdempotent() {
        byte[] zip = skill("pdf-tools", Map.of());

        HttpResponse<String> first = publish(zip);
        HttpResponse<String> second = publish(zip);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode())
                .as("a replay is a success with a different status, not an error")
                .isEqualTo(200);

        JsonNode before = JSON.readTree(first.body());
        JsonNode after = JSON.readTree(second.body());

        assertThat(after.get("created").asBoolean()).isFalse();
        assertThat(after.get("id").asText())
                .as("the same skill, not a second one that happens to share the name")
                .isEqualTo(before.get("id").asText());
        assertThat(after.get("version").get("digest").asText())
                .isEqualTo(before.get("version").get("digest").asText());
        assertThat(count("SELECT count(*) FROM skill_version")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM skill")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM version_file")).isEqualTo(1);
    }

    @Test
    void aRepublishMovesTheCurrentVersionPointerOnlyWhenThereIsANewVersion() {
        byte[] first = skill("pdf-tools", Map.of("references/a.md", "a"));
        byte[] second = skill("pdf-tools", Map.of("references/b.md", "b"));

        publish(first);
        String pointerAfterFirst = currentVersionId();
        publish(second);
        String pointerAfterSecond = currentVersionId();
        publish(second);

        assertThat(pointerAfterSecond)
                .as("new content becomes current, or the second publish did nothing")
                .isNotEqualTo(pointerAfterFirst);
        assertThat(currentVersionId())
                .as("a replay must not move the pointer — that would be rollback, which §7 gives P2")
                .isEqualTo(pointerAfterSecond);
        assertThat(count("SELECT count(*) FROM skill_version")).isEqualTo(2);
    }

    @Test
    void twoSkillsSharingAFileStoreTheBytesOnce() {
        // Contract libraries share boilerplate, so the same bytes arriving under two skills is the
        // normal case rather than a corner. Deduplication is by content hash, which is also why a
        // blob cannot be deleted while any version still references it.
        String shared = "a shared glossary";

        publish(skill("first", Map.of("references/glossary.md", shared)));
        publish(skill("second", Map.of("references/glossary.md", shared)));

        String sha = sha256Hex(shared.getBytes(StandardCharsets.UTF_8));
        assertThat(count("SELECT count(*) FROM version_file WHERE blob_sha256 = :sha",
                Map.of("sha", sha)))
                .as("both versions point at the same content")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM blob_content WHERE sha256 = :sha", Map.of("sha", sha)))
                .as("the bytes are stored once")
                .isEqualTo(1);
    }

    @Test
    void aRejectedUploadComesBackAsAnEnvelopeWithAFieldLevelReason() {
        // What is under test is the mapping, not the rejection: M5 already refuses symlinks in a
        // unit test. What exists only at this level is the shape a client has to parse.
        HttpResponse<String> response = publish(Zips.withSymlink("SKILL.md", "/etc/passwd"));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body()).get("error");

        assertThat(error.get("code").asText()).isEqualTo("invalid_upload");
        assertThat(error.get("message").asText()).contains("symbolic link");
        assertThat(error.get("details").get(0).get("field").asText()).isEqualTo("SKILL.md");
        assertThat(error.get("details").get(0).get("issue").asText()).isEqualTo("symlink_not_allowed");
    }

    @Test
    void aRequestWithoutAFilePartIsRejectedOnThePartItIsMissing() {
        HttpResponse<String> response =
                post(Multipart.create().file("not-the-file", "x.zip", new byte[0]));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("invalid_upload");
        assertThat(error.get("details").get(0).get("field").asText()).isEqualTo("file");
        assertThat(error.get("details").get(0).get("issue").asText()).isEqualTo("missing");
    }

    @Test
    void aBodyBeyondTheMultipartCeilingIsRefusedBeforeAnythingReadsIt() {
        // The ceiling is not the skill limit. It exists so that an absurd body is dropped by the
        // container instead of being buffered and then judged — the validator's answer is the
        // useful one for a legal-but-oversized skill, and this is the backstop for everything else.
        byte[] oversized = new byte[33 * 1024 * 1024];
        new Random(1).nextBytes(oversized);

        HttpResponse<String> response = post(Multipart.create().file("file", "huge.zip", oversized));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("invalid_upload");
    }

    @Test
    void aBlobIsReclaimedOnceNoVersionReferencesIt() {
        // The only way to orphan a blob in P0 is to remove a version, and no P0 operation does
        // that (§3.3 point 5: soft delete keeps every version). So the row is removed directly —
        // what is under test is the sweep, not how a version comes to disappear.
        publish(skill("doomed", Map.of("references/only-here.md", "unreferenced content")));
        assertThat(count("SELECT count(*) FROM blob_content")).isEqualTo(2);

        jdbc.sql("DELETE FROM skill_version WHERE skill_id ="
                + " (SELECT id FROM skill WHERE name = 'doomed')").update();

        // Any publish runs the sweep; this one is only the trigger.
        publish(skill("survivor", Map.of()));

        assertThat(count("SELECT count(*) FROM blob_content"))
                .as("the orphaned bytes are gone, and the trigger's own are not")
                .isEqualTo(1);
    }

    @Test
    void deletingASkillIsASoftDeleteAndASecondDeleteIs404() {
        String id = publishAndReadId(skill("pdf-tools", Map.of()));

        assertThat(delete(id).statusCode()).isEqualTo(204);
        assertThat(delete(id).statusCode())
                .as("already deleted is indistinguishable from never existed, on purpose")
                .isEqualTo(404);

        assertThat(count("SELECT count(*) FROM skill WHERE id = :id AND deleted_at IS NOT NULL",
                Map.of("id", id)))
                .as("§3.3 point 5: the row and its versions survive, so the delete can be undone")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM skill_version WHERE skill_id = :id", Map.of("id", id)))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM blob_content"))
                .as("a soft delete leaves every version referenced, so nothing is garbage yet")
                .isEqualTo(1);
    }

    @Test
    void anotherUsersSkillCannotBeDeleted() {
        // The single P0 token cannot express a second user, so the other user's skill is inserted
        // directly. What is under test is the ownership check, not how the row got there.
        String at = Timestamps.now();
        String otherSkillId = Ulid.generate();
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, description, frontmatter,
                                   created_by, created_at, updated_at)
                VALUES (:id, :namespace, 'not-mine', 'someone else''s', '{}', :owner, :at, :at)
                """)
                .param("id", otherSkillId)
                .param("namespace", OTHER_NAMESPACE_ID)
                .param("owner", OTHER_USER_ID)
                .param("at", at)
                .update();

        assertThat(delete(otherSkillId).statusCode())
                .as("404 rather than 403: a 403 would confirm the skill exists")
                .isEqualTo(404);
        assertThat(count("SELECT count(*) FROM skill WHERE id = :id AND deleted_at IS NULL",
                Map.of("id", otherSkillId)))
                .as("and it is still there")
                .isEqualTo(1);
    }

    @Test
    void publishingOverADeletedNameIsRefusedRatherThanResurrectingIt() {
        byte[] zip = skill("pdf-tools", Map.of());
        String id = publishAndReadId(zip);
        delete(id);

        HttpResponse<String> response = publish(zip);

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("invalid_request");
        assertThat(error.get("message").asText()).contains("deleted");
        assertThat(count("SELECT count(*) FROM skill WHERE id = :id AND deleted_at IS NULL",
                Map.of("id", id)))
                .as("§4.3 gives restoring its own endpoint; a publish must not be a back door to it")
                .isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private HttpResponse<String> publish(byte[] zip) {
        return post(Multipart.create().file("file", "skill.zip", zip));
    }

    private HttpResponse<String> post(Multipart multipart) {
        return send(request("/v1/skills", token())
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private HttpResponse<String> delete(String skillId) {
        return send(request("/v1/skills/" + skillId, token()).DELETE().build());
    }

    private String publishAndReadId(byte[] zip) {
        HttpResponse<String> response = publish(zip);
        assertThat(response.statusCode()).as("publish failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("id").asText();
    }

    private String currentVersionId() {
        return jdbc.sql("SELECT current_version_id FROM skill").query(String.class).single();
    }

    /** A zip holding one skill, wrapped in a directory named after it — what `zip -r x.zip name/` makes. */
    private static byte[] skill(String name, Map<String, String> extraFiles) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name + "/SKILL.md", skillMd(name));
        extraFiles.forEach((relpath, content) -> files.put(name + "/" + relpath, content));
        return Zips.ofText(files);
    }

    private static String skillMd(String name) {
        return "---\nname: " + name + "\ndescription: A skill used by the publish tests\n---\n# " + name + "\n";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }
}
