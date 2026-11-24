package com.shiguang.photograph.updates;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AppUpdateController {
    private final GithubReleases releases;

    AppUpdateController(GithubReleases releases) {
        this.releases = releases;
    }

    @GetMapping("/api/v1/app-update")
    GithubReleases.UpdateInfo latest() {
        return releases.latest();
    }
}
