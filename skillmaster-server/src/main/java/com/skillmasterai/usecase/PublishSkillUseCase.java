package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestedFile;
import com.skillmasterai.modules.ingest.SkillUpload;
import com.skillmasterai.modules.ingest.SkillUploadValidator;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.PublishOutcome;
import com.skillmasterai.modules.version.SkillMetadata;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.usecase.model.PublishedSkill;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes one skill from an uploaded zip.
 *
 * <p>This class is the whole reason §2.5 has a use-case layer. Publishing spans five modules —
 * validate (M5), resolve the namespace (M4), store the bytes (M6), record the version (M7), leave
 * a trace (M10) — and the only correct place for a boundary that covers all five is one level above
 * all of them. Every module it calls is written to be called inside a transaction; none of them
 * opens one.
 *
 * <p><strong>The encryption-of-content rule lives at this boundary too.</strong> The bytes go from
 * the request straight into the blob store and are never written anywhere else: no temp file, no
 * normalisation, no re-encoding. ADR 0005 makes that a correctness requirement, not hygiene —
 * anything that alters the bytes alters the digest, and the digest is what clients trust.
 */
@Component
public class PublishSkillUseCase {

    private final SkillUploadValidator validator;
    private final NamespaceService namespaces;
    private final BlobStore blobs;
    private final SkillVersionService versions;
    private final AuditLog audit;
    private final ObjectMapper objectMapper;

    public PublishSkillUseCase(SkillUploadValidator validator, NamespaceService namespaces,
            BlobStore blobs, SkillVersionService versions, AuditLog audit, ObjectMapper objectMapper) {
        this.validator = validator;
        this.namespaces = namespaces;
        this.blobs = blobs;
        this.versions = versions;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** @param zip the uploaded archive, exactly as received */
    @Transactional
    public PublishedSkill publish(byte[] zip, AuthenticatedSubject subject) {
        SkillUpload upload = validator.validate(zip);

        // Publishing into another namespace is not something the API can express: the target comes
        // from the token's subject, never from a request parameter. Otherwise a publish would be a
        // cross-namespace write primitive.
        Namespace namespace = namespaces.personalNamespaceOf(subject.userId());

        List<ManifestEntry> entries = new ArrayList<>(upload.files().size());
        for (IngestedFile file : upload.files()) {
            BlobStore.BlobRef ref = blobs.put(file.bytes());
            entries.add(new ManifestEntry(file.relpath(), ref.sha256Hex(), ref.size(), file.isBinary()));
        }

        SkillMetadata metadata = new SkillMetadata(upload.name(), upload.title(), upload.description(),
                objectMapper.writeValueAsString(upload.frontmatter()));

        PublishOutcome outcome = versions.publish(
                namespace.id(), metadata, Manifest.of(entries), subject.userId(), "zip");

        // In this same transaction on purpose: an audit row that can commit while the change it
        // describes rolls back is worse than no audit row, because it reads as evidence.
        audit.record(new AuditEvent(subject.userId(), "publish", "skill", outcome.skillId(),
                Map.of("name", upload.name(), "digest", outcome.digest(), "created", outcome.created())));

        return new PublishedSkill(outcome.skillId(), upload.name(), namespace.slug(),
                outcome.digest(), outcome.fileCount(), outcome.totalBytes(), outcome.publishedAt(),
                outcome.created());
    }
}
