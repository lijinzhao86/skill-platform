package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole loop, in the order a real client walks it: publish, find it, look at it, read it.
 *
 * <p>Every other test in this package isolates one thing — this one exists because the failures
 * that matter are the ones at the seams. A digest that is computed one way when storing and another
 * way when serving, a manifest ordered differently in two places, a body that is reformatted
 * somewhere between the archive and the response: each of those passes every unit test and breaks
 * only when the pieces are used in sequence.
 *
 * <p>It is also the regression test for the manual walk-through in {@code README.md}. That file
 * promises a sequence of commands works on a fresh server; if this test passes, the sequence's
 * shape is still right, and the README explains how to run it by hand against a real one.
 */
@Sql("/sql/truncate-business-tables.sql")
class ServerSmokeIT extends AbstractIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Non-ASCII in both the frontmatter and the body, because that is the real corpus (§1.6). */
    private static final String SKILL_MD = """
            ---
            name: feishu-tasks
            description: 飞书任务：查询与创建
            metadata:
              platform_api_version: "1"
            ---
            # 飞书任务

            用之前先读 `references/fields.md`。
            """;

    private static final String FIELDS_MD = "# 字段\n\n- 标题\n- 截止时间\n";

    @Test
    void aClientCanPublishOneSkillAndReadItBackAtEveryLevel() {
        HttpResponse<String> published = publish();
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(201);
        JsonNode publishBody = JSON.readTree(published.body());
        String id = publishBody.get("id").asText();
        String digest = publishBody.get("version").get("digest").asText();

        // L1, as a search result: names and descriptions, nothing else.
        JsonNode found = JSON.readTree(
                get("/v1/skills?q=%E9%A3%9E%E4%B9%A6", token()).body()).get("skills");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("id").asText()).isEqualTo(id);
        assertThat(found.get(0).get("digest").asText())
                .as("the listing and the publish response agree on what is current")
                .isEqualTo(digest);

        // L1, as a detail: the whole manifest and no content.
        JsonNode detail = JSON.readTree(get("/v1/skills/" + id, token()).body());
        assertThat(detail.get("files")).hasSize(2);
        assertThat(detail.get("frontmatter").get("metadata").get("platform_api_version").asText())
                .as("unknown fields survive the round trip (§3.3)")
                .isEqualTo("1");
        assertThat(detail.toString())
                .as("detail is the manifest; content comes from L2 and L3 only")
                .doesNotContain("用之前先读")
                .doesNotContain("截止时间");

        // L2: the original bytes, frontmatter included.
        assertThat(new String(getBytes("/v1/skills/" + id + "/body", token()).body(),
                StandardCharsets.UTF_8))
                .as("byte for byte what was uploaded — ADR 0005's digest describes these bytes")
                .isEqualTo(SKILL_MD);

        // L3: one file, by the path the manifest named.
        assertThat(new String(
                getBytes("/v1/skills/" + id + "/files/references/fields.md", token()).body(),
                StandardCharsets.UTF_8))
                .isEqualTo(FIELDS_MD);

        // And out again.
        assertThat(send(request("/v1/skills/" + id, token()).DELETE().build()).statusCode())
                .isEqualTo(204);
        assertThat(get("/v1/skills/" + id, token()).statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(get("/v1/skills?q=%E9%A3%9E%E4%B9%A6", token()).body())
                .get("skills"))
                .as("a deleted skill is gone from search too, not only from detail")
                .isEmpty();
    }

    @Test
    void theDigestIsStableAcrossAPublishAndARepublish() {
        // The property a client depends on to decide whether anything changed. It is checked here
        // at the end of the loop rather than only at publish time, because a digest is only useful
        // if the same bytes produce the same value on two different days.
        String first = JSON.readTree(publish().body()).get("version").get("digest").asText();
        String second = JSON.readTree(publish().body()).get("version").get("digest").asText();

        assertThat(second).isEqualTo(first);
    }

    private HttpResponse<String> publish() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("feishu-tasks/SKILL.md", SKILL_MD);
        files.put("feishu-tasks/references/fields.md", FIELDS_MD);
        Multipart multipart = Multipart.create().file("file", "feishu-tasks.zip",
                Zips.ofText(files));
        return send(request("/v1/skills", token())
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }
}
