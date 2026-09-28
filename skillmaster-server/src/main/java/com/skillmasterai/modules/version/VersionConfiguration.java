package com.skillmasterai.modules.version;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.version.internal.BlobGc;
import com.skillmasterai.modules.version.internal.SkillCatalogRepository;
import com.skillmasterai.modules.version.internal.SkillRepository;
import com.skillmasterai.modules.version.internal.VersionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires M7.
 *
 * <p>M7 depends on M6's {@link BlobStore} interface and not the other way round, which is what
 * keeps the two modules acyclic: references live in M7's {@code version_file}, bytes live in M6,
 * and reclaiming one because of the other has to be driven from the side that knows what is
 * referenced.
 */
@Configuration(proxyBeanMethods = false)
public class VersionConfiguration {

    @Bean
    SkillRepository skillRepository(JdbcClient jdbc) {
        return new SkillRepository(jdbc);
    }

    @Bean
    VersionRepository versionRepository(JdbcClient jdbc) {
        return new VersionRepository(jdbc);
    }

    @Bean
    BlobGc blobGc(JdbcClient jdbc, BlobStore blobs) {
        return new BlobGc(jdbc, blobs);
    }

    @Bean
    SkillVersionService skillVersionService(SkillRepository skills, VersionRepository versions,
            BlobGc blobGc) {
        return new SkillVersionService(skills, versions, blobGc);
    }

    @Bean
    SkillCatalogRepository skillCatalogRepository(JdbcClient jdbc) {
        return new SkillCatalogRepository(jdbc);
    }

    @Bean
    SkillCatalogService skillCatalogService(SkillCatalogRepository repository) {
        return new SkillCatalogService(repository);
    }
}
