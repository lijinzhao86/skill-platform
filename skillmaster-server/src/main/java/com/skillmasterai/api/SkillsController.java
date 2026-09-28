package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.search.SearchRequest;
import com.skillmasterai.usecase.PublishSkillUseCase;
import com.skillmasterai.usecase.ReadSkillUseCase;
import com.skillmasterai.usecase.SearchSkillsUseCase;
import com.skillmasterai.usecase.SoftDeleteSkillUseCase;
import com.skillmasterai.usecase.model.PublishedSkill;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The two write endpoints of §4.3.
 *
 * <p>Thin by design: every decision this class could make is already made somewhere that can be
 * tested without HTTP. It resolves the caller, reads one part, and picks a status code.
 *
 * <p>There is <strong>no {@code namespace} parameter</strong> on publish, and that is a decision
 * rather than an omission — see {@link PublishSkillUseCase}. The same goes for {@code visibility}:
 * it is not offered here because §4.2 gives metadata its own endpoint, and accepting it on a
 * publish would make a private skill public by way of a field nobody was looking at.
 */
@RestController
@RequestMapping(path = "/v1/skills")
class SkillsController {

    private final PublishSkillUseCase publishSkill;
    private final SoftDeleteSkillUseCase softDeleteSkill;
    private final ReadSkillUseCase readSkill;
    private final SearchSkillsUseCase searchSkills;
    private final ObjectMapper objectMapper;

    SkillsController(PublishSkillUseCase publishSkill, SoftDeleteSkillUseCase softDeleteSkill,
            ReadSkillUseCase readSkill, SearchSkillsUseCase searchSkills,
            ObjectMapper objectMapper) {
        this.publishSkill = publishSkill;
        this.softDeleteSkill = softDeleteSkill;
        this.readSkill = readSkill;
        this.searchSkills = searchSkills;
        this.objectMapper = objectMapper;
    }

    /**
     * Search, or browse (§4.2).
     *
     * <p>Every parameter is optional, so a bare {@code GET /v1/skills} is a valid request: it lists
     * the caller's own skills newest first. That is the natural first thing a client does, and
     * requiring a query string to do it would be an odd gate.
     *
     * <p>{@code namespace} narrows within what the caller may already see. It is not a bypass, and
     * it cannot be: the ownership predicate is in the query regardless of what this parameter says,
     * so naming someone else's namespace returns nothing rather than their skills.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SearchResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        // sort arrives as text and is parsed rather than bound to the enum: §4.2's values are
        // lowercase, and binding would have made the constant name the wire name — so the
        // documented `sort=recent` would have been an error and `sort=RECENT` would have worked.
        SearchRequest request = new SearchRequest(q, namespace,
                sort == null ? SearchRequest.SortOrder.RELEVANCE : SearchRequest.SortOrder.fromWire(sort),
                limit == null ? SearchRequest.DEFAULT_LIMIT : limit,
                cursor);
        return ResponseEntity.ok(SearchResponse.of(searchSkills.search(request, subject)));
    }

    /**
     * Detail and full manifest, with no content (§4.2 L1).
     *
     * <p>404 covers three cases and does not distinguish them: no such skill, a skill in a
     * namespace the caller does not own, and an id that is not an id. The first two are §4.2's
     * requirement — a 403 would confirm existence — and the third needs no special handling,
     * because a malformed id simply matches no row.
     */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillDetailResponse> detail(@PathVariable String id,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return readSkill.detail(id, subject)
                .map(detail -> ResponseEntity.ok(SkillDetailResponse.of(detail, objectMapper)))
                .orElseGet(() -> ResponseEntity.<SkillDetailResponse>notFound().build());
    }

    /**
     * The original {@code SKILL.md} bytes (§4.2 L2).
     *
     * <p>No {@code produces} attribute: the type is fixed but it is not negotiable, and declaring
     * it here would let content negotiation reject a client that asked for something else rather
     * than simply serving the bytes. The body is what the author wrote, and reformatting or
     * re-encoding it to satisfy an {@code Accept} header would break ADR 0005's digest.
     */
    @GetMapping("/{id}/body")
    ResponseEntity<byte[]> body(@PathVariable String id,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return readSkill.body(id, subject)
                .map(bytes -> ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, MediaTypes.MARKDOWN)
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.<byte[]>notFound().build());
    }

    /**
     * One file's original bytes (§4.2 L3).
     *
     * <p>{@code {*relpath}} takes the rest of the path, slashes included, so the value that arrives
     * here is whatever the client sent after being decoded. It is used as a <strong>lookup key</strong>
     * against the stored manifest and never as a path — no normalisation, no resolution, no
     * filesystem. That is what makes §4.2's exact-match rule unfalsifiable rather than merely
     * intended: a name that means something elsewhere simply matches no row, and the answer is 404.
     */
    @GetMapping("/{id}/files/{*relpath}")
    ResponseEntity<byte[]> file(@PathVariable String id, @PathVariable String relpath,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return readSkill.file(id, CapturedPath.relativeToSkillRoot(relpath), subject)
                .map(stored -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE,
                                MediaTypes.forRelpath(stored.entry().relpath()))
                        .body(stored.bytes()))
                .orElseGet(() -> ResponseEntity.<byte[]>notFound().build());
    }

    /**
     * Publishes one skill from a zip.
     *
     * <p>The whole part is read into memory before validation. That is bounded — {@code
     * spring.servlet.multipart.max-file-size} rejects an oversized body before this method runs —
     * and it is required by ADR 0005: the digest is taken over the bytes exactly as received, so
     * anything that streams them through a file or a buffer that could rewrite them is a
     * correctness bug, not an optimisation.
     *
     * <p>200 rather than 201 when the content already existed. The distinct status is the only
     * signal a client needs to tell a first publish from an idempotent replay; making it also
     * compare digests would push a rule the server already knows onto every caller.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublishResponse> publish(@RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        byte[] zip;
        try {
            zip = file.getBytes();
        } catch (IOException e) {
            // The part was announced but could not be read off the wire — a truncated upload,
            // not a validation failure. Reported as a bad upload rather than a 500 because the
            // only party who can fix it is the caller.
            throw new IngestException("the uploaded file could not be read", "file", "unreadable");
        }

        PublishedSkill published = publishSkill.publish(zip, subject);
        return ResponseEntity
                .status(published.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(PublishResponse.of(published));
    }

    /**
     * Soft-deletes a skill (§4.3).
     *
     * <p>404 for "not yours" as well as "not there" — the two are indistinguishable by design, so
     * that a delete cannot be used to probe for the existence of another user's private skill.
     */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return softDeleteSkill.softDelete(id, subject)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
