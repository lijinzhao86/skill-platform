package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.gateway.GatewayService;
import com.skillmasterai.modules.gateway.GatewaySource;
import com.skillmasterai.usecase.model.PublishedSkill;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes the gateway skill from the repository's source, as part of the running system.
 *
 * <p>This is the only skill published without a request behind it, so the reason is worth stating.
 * §1.5's discovery channel has no authentication, so the one artefact it serves has to be published
 * by something that needs no token; and §5.2 requires the published gateway to match the running
 * server, because the gateway body <em>is</em> the protocol — a stale one makes every client speak
 * the wrong dialect.
 *
 * <p>It goes through the ordinary publish path rather than writing rows directly. That means the
 * gateway obeys the same rules as any other skill (a frontmatter with no description is refused
 * here too), and its content is stored and digested by exactly one code path. Because publishing
 * identical content is idempotent (ADR 0005), running this on every start inserts nothing once the
 * content has settled — which is what lets §5.2's "the gateway must be updatable" hold without any
 * version bookkeeping of our own.
 *
 * <p>Transactionality is inherited: {@link PublishSkillUseCase#publish} opens the boundary and this
 * does no work outside it.
 */
@Component
public class PublishGatewaySkillUseCase {

    /**
     * The user the gateway skill is attributed to: the {@code skillmaster} account seeded by
     * {@code V2__seed_owner_and_namespaces.sql}, which owns the reserved namespace.
     *
     * <p>Attribution is not incidental — every version records who published it, and the honest
     * answer here is the system account rather than whichever operator started the process.
     */
    private static final String SYSTEM_USER_ID = "01M3HTG7GDZGTDME9B136ZVAW4";

    private final PublishSkillUseCase publishSkill;

    public PublishGatewaySkillUseCase(PublishSkillUseCase publishSkill) {
        this.publishSkill = publishSkill;
    }

    /**
     * @param publicBaseUrl the address clients reach this deployment at, written into the source's
     *                      placeholder
     */
    @Transactional
    public PublishedSkill publish(String publicBaseUrl) {
        return publishSkill.publish(zipOf(GatewaySource.read(publicBaseUrl)), systemSubject());
    }

    private static AuthenticatedSubject systemSubject() {
        return new AuthenticatedSubject(SYSTEM_USER_ID, Set.of());
    }

    /**
     * Builds the archive the publish path expects.
     *
     * <p>A zip rather than a shortcut into M5's internals: the archive genuinely is that path's
     * input format, and one file is not a special case worth its own entry point.
     *
     * <p>The entry is nested under the skill's name because §1.3 requires a skill's directory to be
     * named after it and M5 enforces that — which also makes this look like every other upload,
     * {@code zip -r x.zip skillmaster/}.
     */
    private static byte[] zipOf(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.putArchiveEntry(new ZipArchiveEntry(
                        GatewayService.SKILL_NAME + "/" + file.getKey()));
                out.write(file.getValue());
                out.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not build the gateway archive", e);
        }
        return bytes.toByteArray();
    }
}
