/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven.internal;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.vavr.CheckedFunction1;
import okhttp3.*;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.cache.CacheResult;
import org.openrewrite.maven.cache.MavenPomCache;
import org.openrewrite.maven.tree.MavenRepository;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class MavenPomDownloader {
    private static final RetryConfig retryConfig = RetryConfig.custom()
            .retryExceptions(SocketTimeoutException.class, TimeoutException.class)
            .build();

    private static final RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectionSpecs(Arrays.asList(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .build();

    private static final Retry mavenDownloaderRetry = retryRegistry.retry("MavenDownloader");

    private static final CheckedFunction1<Request, Response> sendRequest = Retry.decorateCheckedFunction(
            mavenDownloaderRetry,
            (request) -> httpClient.newCall(request).execute());

    // https://maven.apache.org/ref/3.6.3/maven-model-builder/super-pom.html
    private static final MavenRepository SUPER_POM_REPOSITORY = new MavenRepository("central",
            URI.create("https://repo.maven.apache.org/maven2"), true, false, null, null);

    private final MavenPomCache mavenPomCache;
    private final Map<Path, RawMaven> projectPoms;
    private final ExecutionContext ctx;

    public MavenPomDownloader(MavenPomCache mavenPomCache, Map<Path, RawMaven> projectPoms, ExecutionContext ctx) {
        this.mavenPomCache = mavenPomCache;
        this.projectPoms = projectPoms;
        this.ctx = ctx;
    }

    public MavenMetadata downloadMetadata(String groupId, String artifactId, Collection<MavenRepository> repositories) {
        Timer.Sample sample = Timer.start();

        return Stream.concat(repositories.stream().distinct(), Stream.of(SUPER_POM_REPOSITORY))
                .map(this::normalizeRepository)
                .distinct()
                .filter(Objects::nonNull)
                .map(repo -> {
                    Timer.Builder timer = Timer.builder("rewrite.maven.download")
                            .tag("group.id", groupId)
                            .tag("artifact.id", artifactId)
                            .tag("type", "metadata");

                    try {
                        CacheResult<MavenMetadata> result = mavenPomCache.computeMavenMetadata(repo.getUri(), groupId, artifactId,
                                () -> forceDownloadMetadata(groupId, artifactId, null, repo));

                        sample.stop(addTagsByResult(timer, result).register(Metrics.globalRegistry));
                        return result.getData();
                    } catch (Exception e) {
                        sample.stop(timer.tags("outcome", "error", "exception", e.getClass().getName())
                                .register(Metrics.globalRegistry));
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .reduce(MavenMetadata.EMPTY, (m1, m2) -> {
                    if (m1 == MavenMetadata.EMPTY) {
                        if (m2 == MavenMetadata.EMPTY) {
                            return m1;
                        } else {
                            return m2;
                        }
                    } else if (m2 == MavenMetadata.EMPTY) {
                        return m1;
                    } else {
                        return new MavenMetadata(new MavenMetadata.Versioning(
                                Stream.concat(m1.getVersioning().getVersions().stream(),
                                        m2.getVersioning().getVersions().stream()).collect(toList()),
                                null
                        ));
                    }
                });
    }

    @Nullable
    private MavenMetadata forceDownloadMetadata(String groupId, String artifactId, @Nullable String version, MavenRepository repo) throws IOException {
        String uri = repo.getUri().toString() + "/" +
                groupId.replace('.', '/') + '/' +
                artifactId + '/' +
                (version == null ? "" : version + '/') +
                "maven-metadata.xml";

        Request.Builder request = applyAuthentication(repo, new Request.Builder().url(uri).get());
        try (Response response = sendRequest.apply(request.build())) {
            if (response.isSuccessful() && response.body() != null) {
                @SuppressWarnings("ConstantConditions") byte[] responseBody = response.body()
                        .bytes();

                return MavenMetadata.parse(responseBody);
            }
        } catch (Throwable throwable) {
            return null;
        }

        return null;
    }

    private Timer.Builder addTagsByResult(Timer.Builder timer, CacheResult<?> result) {
        switch (result.getState()) {
            case Cached:
                timer = timer.tags("outcome", "cached", "exception", "none");
                break;
            case Unavailable:
                timer = timer.tags("outcome", "unavailable", "exception", "none");
                break;
            case Updated:
                timer = timer.tags("outcome", "downloaded", "exception", "none");
                break;
        }
        return timer;
    }

    @Nullable
    public RawMaven download(String groupId,
                             String artifactId,
                             String version,
                             @Nullable String relativePath,
                             @Nullable RawMaven containingPom,
                             Collection<MavenRepository> repositories,
                             ExecutionContext ctx) {
        try {
            String versionMaybeDatedSnapshot = findDatedSnapshotVersionIfNecessary(groupId, artifactId, version, repositories);
            if (versionMaybeDatedSnapshot == null) {
                return null;
            }

            Timer.Sample sample = Timer.start();

            // The pom being examined might be from a remote repository or a local filesystem.
            // First try to match the requested download with one of the project poms so we don't needlessly ping remote repos
            for (RawMaven projectPom : projectPoms.values()) {
                if (groupId.equals(projectPom.getPom().getGroupId()) &&
                        artifactId.equals(projectPom.getPom().getArtifactId())) {
                    return projectPom;
                }
            }
            if (containingPom != null && !StringUtils.isBlank(relativePath)) {
                Path folderContainingPom = containingPom.getSourcePath()
                        .getParent();
                if (folderContainingPom != null) {
                    RawMaven maybeLocalPom = projectPoms.get(folderContainingPom.resolve(Paths.get(relativePath, "pom.xml"))
                            .normalize());
                    // Even poms published to remote repositories still contain relative paths to their parent poms
                    // So double check that the GAV coordinates match so that we don't get a relative path from a remote
                    // pom like ".." or "../.." which coincidentally _happens_ to have led to an unrelated pom on the local filesystem
                    if (maybeLocalPom != null
                            && groupId.equals(maybeLocalPom.getPom().getGroupId())
                            && artifactId.equals(maybeLocalPom.getPom().getArtifactId())
                            && version.equals(maybeLocalPom.getPom().getVersion())) {
                        return maybeLocalPom;
                    }
                }
            }

            return Stream.concat(repositories.stream(), Stream.of(SUPER_POM_REPOSITORY))
                    .map(this::normalizeRepository)
                    .filter(Objects::nonNull)
                    .distinct()
                    .filter(repo -> repo.acceptsVersion(version))
                    .map(repo -> {
                        Timer.Builder timer = Timer.builder("rewrite.maven.download")
                                .tag("repo.id", repo.getUri().toString())
                                .tag("group.id", groupId)
                                .tag("artifact.id", artifactId)
                                .tag("type", "pom");

                        try {
                            CacheResult<RawMaven> result = mavenPomCache.computeMaven(repo.getUri(), groupId, artifactId,
                                    versionMaybeDatedSnapshot, () -> {
                                        String uri = URI.create(repo.getUri().toString()) + "/" +
                                                groupId.replace('.', '/') + '/' +
                                                artifactId + '/' +
                                                version + '/' +
                                                artifactId + '-' + versionMaybeDatedSnapshot + ".pom";

                                        Request.Builder request = applyAuthentication(repo, new Request.Builder().url(uri).get());
                                        try (Response response = sendRequest.apply(request.build())) {
                                            if (response.isSuccessful() && response.body() != null) {
                                                @SuppressWarnings("ConstantConditions") byte[] responseBody = response.body()
                                                        .bytes();

                                                // This path doesn't matter except for debugging/error logs where it might get displayed
                                                Path inputPath = Paths.get(groupId, artifactId, version);
                                                return RawMaven.parse(
                                                        new Parser.Input(inputPath, () -> new ByteArrayInputStream(responseBody), true),
                                                        null,
                                                        versionMaybeDatedSnapshot.equals(version) ? null : versionMaybeDatedSnapshot,
                                                        ctx
                                                ).withRepository(repo);
                                            }
                                        } catch (Throwable throwable) {
                                            throw new RuntimeException("Unable to download dependency", throwable);
                                        }

                                        return null;
                                    });

                            sample.stop(addTagsByResult(timer, result).register(Metrics.globalRegistry));
                            return result.getData();
                        } catch (Exception e) {
                            sample.stop(timer.tags("outcome", "error", "exception", e.getClass().getName())
                                    .register(Metrics.globalRegistry));
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Throwable t) {
            ctx.getOnError().accept(t);
            return null;
        }
    }

    @Nullable
    private String findDatedSnapshotVersionIfNecessary(String groupId, String artifactId, String version, Collection<MavenRepository> repositories) {
        if (version.endsWith("-SNAPSHOT")) {
            MavenMetadata mavenMetadata = repositories.stream()
                    .map(this::normalizeRepository)
                    .filter(Objects::nonNull)
                    .distinct()
                    .filter(repo -> repo.acceptsVersion(version))
                    .map(repo -> {
                        try {
                            return forceDownloadMetadata(groupId, artifactId, version, repo);
                        } catch (IOException e) {
                            ctx.getOnError().accept(e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            if (mavenMetadata != null) {
                MavenMetadata.Snapshot snapshot = mavenMetadata.getVersioning().getSnapshot();
                if (snapshot == null) {
                    return null;
                }
                return version.replaceFirst("SNAPSHOT$", snapshot.getTimestamp() + "-" + snapshot.getBuildNumber());
            }
        }

        return version;
    }

    @Nullable
    private MavenRepository normalizeRepository(MavenRepository repository) {
        CacheResult<MavenRepository> result;
        try {
            String originalUrl = repository.getUri().toString();
            result = mavenPomCache.computeRepository(repository, () -> {
                // Always prefer to use https, fallback to http only if https isn't available
                // URLs are case-sensitive after the domain name, so it can be incorrect to lowerCase() a whole URL
                // This regex accepts any capitalization of the letters in "http"
                String httpsUri = repository.getUri().getScheme().equalsIgnoreCase("http") ?
                        repository.getUri().toString().replaceFirst("[hH][tT][tT][pP]://", "https://") :
                        repository.getUri().toString();

                Request.Builder request = applyAuthentication(repository, new Request.Builder()
                        .url(httpsUri).get());
                try (Response response = sendRequest.apply(request.build())) {
                    return response.isSuccessful() ?
                            repository.withUri(URI.create(httpsUri)) :
                            null;
                } catch (SSLException e) {
                    // Fallback to http if https is unavailable and the original URL was an http URL
                    if (httpsUri.equals(originalUrl)) {
                        return null;
                    }
                    try (Response httpResponse = sendRequest.apply(request.url(originalUrl).build())) {
                        if (httpResponse.isSuccessful()) {
                            return new MavenRepository(
                                    repository.getId(),
                                    URI.create(originalUrl),
                                    repository.isReleases(),
                                    repository.isSnapshots(),
                                    repository.getUsername(),
                                    repository.getPassword());
                        }
                    } catch (Throwable t) {
                        return null;
                    }
                } catch (Throwable t) {
                    return null;
                }
                return null;
            });
        } catch (Exception e) {
            return null;
        }

        return result.getData();
    }

    private Request.Builder applyAuthentication(MavenRepository repository, Request.Builder request) {
        if (repository.getUsername() != null && repository.getPassword() != null) {
            String credentials = Credentials.basic(repository.getUsername(), repository.getPassword());
            request.header("Authorization", credentials);
        }
        return request;
    }
}
