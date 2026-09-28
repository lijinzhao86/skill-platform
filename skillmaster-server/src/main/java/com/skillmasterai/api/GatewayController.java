package com.skillmasterai.api;

import com.skillmasterai.modules.gateway.GatewayService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.5: how a machine that has never authenticated finds out where to authenticate.
 *
 * <p>Every route here is <strong>unauthenticated by design</strong>, and that is not an oversight to
 * be tightened later — §1.5 verified that this channel has neither authentication nor any concept
 * of entitlement, which is exactly what makes it usable as a bootstrap. The security configuration
 * permits these paths explicitly, so default-deny stays intact everywhere else.
 *
 * <p>Paths come from {@link GatewayService}'s constants rather than being written out again, because
 * the V2 index advertises one of them. A second literal could drift from the route, and the only
 * symptom would be clients failing to bootstrap for no visible reason.
 *
 * <p>When the gateway skill has not been published, every route is a real 404. §1.5 records why that
 * matters: a host that does not publish this convention answers <em>200 with HTML</em> from an SPA
 * catch-all, and a client cannot tell that from a real index. Serving a template out of the
 * classpath would reproduce the same lie.
 */
@RestController
class GatewayController {

    private final GatewayService gateway;

    GatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    @GetMapping(GatewayService.INDEX_V2_PATH)
    ResponseEntity<GatewayIndexResponse.V2> indexV2() {
        return gateway.indexV2()
                .map(index -> ResponseEntity.ok(GatewayIndexResponse.V2.of(index)))
                .orElseGet(() -> ResponseEntity.<GatewayIndexResponse.V2>notFound().build());
    }

    @GetMapping(GatewayService.INDEX_V1_PATH)
    ResponseEntity<GatewayIndexResponse.V1> indexV1() {
        return gateway.indexV1()
                .map(index -> ResponseEntity.ok(GatewayIndexResponse.V1.of(index)))
                .orElseGet(() -> ResponseEntity.<GatewayIndexResponse.V1>notFound().build());
    }

    /**
     * One file of the gateway skill.
     *
     * <p>{@code provider} is the base the client chose. §1.5 found that the index's advertised base
     * and the path clients fetch from need not agree, so all three known bases serve the same bytes
     * and anything else is a 404 — a stated set of aliases rather than a wildcard that would make
     * every URL under {@code /.well-known/} an entry point.
     */
    @GetMapping(GatewayService.FILE_BASE_PATTERN)
    ResponseEntity<byte[]> file(@PathVariable String provider, @PathVariable String relpath) {
        return gateway.fileOf(provider, CapturedPath.relativeToSkillRoot(relpath))
                .map(file -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaTypes.forRelpath(file.relpath()))
                        .body(file.bytes()))
                .orElseGet(() -> ResponseEntity.<byte[]>notFound().build());
    }

    /** The gateway body — what the CLI's {@code setup} installs and keeps up to date (§5.2). */
    @GetMapping(GatewayService.BODY_PATH)
    ResponseEntity<byte[]> body() {
        return gateway.body()
                .map(file -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaTypes.MARKDOWN)
                        .body(file.bytes()))
                .orElseGet(() -> ResponseEntity.<byte[]>notFound().build());
    }
}
