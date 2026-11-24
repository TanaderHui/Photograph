package com.shiguang.photograph.auth;

import java.util.UUID;

public record AccountPrincipal(UUID accountId, UUID sessionId, String nickname, boolean operator) {}
