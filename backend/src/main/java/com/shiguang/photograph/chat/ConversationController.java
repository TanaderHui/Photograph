package com.shiguang.photograph.chat;

import com.shiguang.photograph.auth.AccountPrincipal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ConversationController {
    private final ConversationService conversations;

    ConversationController(ConversationService conversations) { this.conversations = conversations; }

    @PostMapping("/conversations")
    ConversationService.Conversation open(@AuthenticationPrincipal AccountPrincipal account, @RequestBody Open request) {
        return conversations.open(account.accountId(), request.photographerId(), request.bookingId());
    }

    @GetMapping("/me/conversations")
    ConversationService.Page<ConversationService.Conversation> inbox(@AuthenticationPrincipal AccountPrincipal account,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return conversations.inbox(account.accountId(), cursor, limit);
    }

    @GetMapping("/conversations/{id}/messages")
    ConversationService.Page<ConversationService.Message> history(@AuthenticationPrincipal AccountPrincipal account,
            @PathVariable UUID id, @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Long afterSeq, @RequestParam(defaultValue = "40") int limit) {
        return conversations.history(account.accountId(), id, beforeSeq, afterSeq, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    ConversationService.Message send(@AuthenticationPrincipal AccountPrincipal account,
            @PathVariable UUID id, @RequestBody ConversationService.Send request) {
        return conversations.send(account.accountId(), id, request);
    }

    @PutMapping("/conversations/{id}/read")
    ResponseEntity<Void> read(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id, @RequestBody Read request) {
        conversations.read(account.accountId(), id, request.seq());
        return ResponseEntity.noContent().build();
    }

    record Open(UUID photographerId, UUID bookingId) {}
    record Read(Long seq) {}
}
