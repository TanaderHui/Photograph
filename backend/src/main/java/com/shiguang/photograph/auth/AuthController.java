package com.shiguang.photograph.auth;

import com.shiguang.photograph.api.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AuthService auth;

    AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/otp/challenges")
    ResponseEntity<Challenge> request(@RequestBody Phone request) {
        UUID id = auth.requestCode(request.phone());
        return ResponseEntity.created(URI.create("/api/v1/auth/otp/challenges/" + id))
                .cacheControl(CacheControl.noStore()).body(new Challenge(id, 300));
    }

    @PostMapping("/sessions")
    ResponseEntity<AuthService.SessionTokens> login(@RequestBody Verification request) {
        var tokens = auth.login(request.challengeId(), request.code()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_OTP", "验证码无效或已过期。"));
        return ResponseEntity.created(URI.create("/api/v1/me/profile"))
                .cacheControl(CacheControl.noStore()).body(tokens);
    }

    @PostMapping("/sessions/refresh")
    ResponseEntity<AuthService.SessionTokens> refresh(@RequestBody Refresh request) {
        var tokens = auth.refresh(request.refreshToken()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请重新登录。"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens);
    }

    @PostMapping("/sessions/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal AccountPrincipal principal) {
        auth.logout(principal);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/account")
    ResponseEntity<Void> closeAccount(@AuthenticationPrincipal AccountPrincipal principal) {
        auth.closeAccount(principal);
        return ResponseEntity.noContent().build();
    }

    record Phone(String phone) {}
    record Verification(UUID challengeId, String code) {}
    record Refresh(String refreshToken) {}
    record Challenge(UUID challengeId, int expiresIn) {}
}
