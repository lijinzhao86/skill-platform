package com.skillmasterai.modules.distribution;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.version.SkillVersionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires M9.
 *
 * <p>M9 owns no tables (§2.5): it answers with M7's versions and M6's bytes. Both dependencies
 * point one way — M7 knows nothing about M9, M6 knows nothing about either — so the three stay
 * acyclic, which is the property {@code ArchitectureTest.modulesAreFreeOfCycles} checks.
 */
@Configuration(proxyBeanMethods = false)
public class DistributionConfiguration {

    @Bean
    SkillDistributionService skillDistributionService(SkillVersionService versions, BlobStore blobs) {
        return new SkillDistributionService(versions, blobs);
    }
}
