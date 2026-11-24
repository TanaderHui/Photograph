package com.shiguang.photograph.updates;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiguang.photograph.api.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
class GithubReleases {
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}");
    private static final Pattern VERSION_TAG = Pattern.compile("v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}");
    private static final Pattern APK_NAME = Pattern.compile("photograph-([1-9][0-9]{0,9})\\.apk");
    private static final Duration CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration ERROR_TTL = Duration.ofSeconds(30);

    private final String repository;
    private final RestClient github;
    private Cached cached;

    @Autowired
    GithubReleases(@Value("${photograph.updates.github-repository}") String repository) {
        this(repository, githubClient());
    }

    GithubReleases(String repository, RestClient github) {
        if (!repository.isEmpty() && !REPOSITORY.matcher(repository).matches()) {
            throw new IllegalArgumentException("PHOTOGRAPH_GITHUB_RELEASE_REPOSITORY must be owner/repo");
        }
        this.repository = repository;
        this.github = github;
    }

    synchronized UpdateInfo latest() {
        if (repository.isEmpty()) return new UpdateInfo(false, null);
        if (cached == null || !cached.until().isAfter(Instant.now())) {
            try {
                cached = new Cached(new UpdateInfo(true, fetch()), Instant.now().plus(CACHE_TTL));
            } catch (RestClientException error) {
                cached = new Cached(null, Instant.now().plus(ERROR_TTL));
            }
        }
        if (cached.info() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPDATE_SOURCE_UNAVAILABLE", "暂时无法检查更新，请稍后重试。");
        }
        return cached.info();
    }

    private Release fetch() {
        GithubRelease release;
        try {
            String[] path = repository.split("/");
            release = github.get().uri("/repos/{owner}/{repo}/releases/latest", path[0], path[1])
                    .retrieve().body(GithubRelease.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode() == HttpStatus.NOT_FOUND) return null;
            throw error;
        }
        if (release == null || release.draft() || release.prerelease() || release.tag() == null
                || !VERSION_TAG.matcher(release.tag()).matches() || release.assets() == null) {
            return null;
        }

        String prefix = "https://github.com/" + repository + "/releases/download/" + release.tag() + "/";
        return release.assets().stream().filter(Objects::nonNull)
                .map(asset -> toRelease(release.tag(), asset, prefix))
                .filter(Objects::nonNull).max(Comparator.comparingInt(Release::versionCode)).orElse(null);
    }

    private static Release toRelease(String tag, Asset asset, String prefix) {
        if (!"uploaded".equals(asset.state()) || asset.name() == null || asset.size() <= 0) return null;
        var name = APK_NAME.matcher(asset.name());
        if (!name.matches() || !(prefix + asset.name()).equalsIgnoreCase(asset.downloadUrl())) return null;
        long versionCode = Long.parseLong(name.group(1));
        if (versionCode > 2_100_000_000) return null;
        return new Release((int) versionCode, tag.substring(1), prefix + asset.name());
    }

    private static RestClient githubClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl("https://api.github.com").requestFactory(factory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("User-Agent", "Photograph-Release-Check")
                .build();
    }

    record UpdateInfo(boolean configured, Release release) {}
    record Release(int versionCode, String versionName, String downloadUrl) {}
    private record Cached(UpdateInfo info, Instant until) {}
    private record GithubRelease(@JsonProperty("tag_name") String tag, boolean draft, boolean prerelease, List<Asset> assets) {}
    private record Asset(String name, String state, long size, @JsonProperty("browser_download_url") String downloadUrl) {}
}
