package com.shiguang.photograph.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.shiguang.photograph.auth.AccountPrincipal;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class BookingController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final BookingService bookings;
    private final CalendarService calendars;

    BookingController(BookingService bookings, CalendarService calendars) {
        this.bookings = bookings;
        this.calendars = calendars;
    }

    @GetMapping("/me/photographer/calendar")
    CalendarService.Calendar calendar(@AuthenticationPrincipal AccountPrincipal principal) {
        return calendars.ownCalendar(principal.accountId());
    }

    @PutMapping("/me/photographer/availability")
    CalendarService.Calendar replaceCalendar(@AuthenticationPrincipal AccountPrincipal principal,
                                             @RequestBody CalendarService.CalendarEdit request) {
        return calendars.replace(principal.accountId(), request);
    }

    @PutMapping("/me/photographer/calendar-visibility")
    CalendarService.Calendar setVisibility(@AuthenticationPrincipal AccountPrincipal principal,
                                            @RequestBody CalendarVisibility request) {
        return calendars.visibility(principal.accountId(), request.visibility());
    }

    @GetMapping("/photographers/{id}/availability")
    CalendarService.Availability availability(@PathVariable UUID id, @RequestParam UUID packageId, @RequestParam LocalDate date) {
        return calendars.publicAvailability(id, packageId, date);
    }

    @PostMapping("/bookings")
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key,
            @RequestBody BookingService.BookingRequest request) {
        JsonNode booking = bookings.create(principal.accountId(), key, request);
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + booking.get("id").asText())).body(booking);
    }

    @GetMapping("/me/bookings")
    BookingService.Page<BookingService.View> list(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) UUID cursor, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Boolean completed) {
        return bookings.list(principal.accountId(), cursor, limit, completed);
    }

    @GetMapping("/bookings/{id}")
    BookingService.View get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        return bookings.get(principal.accountId(), id);
    }

    @PostMapping("/bookings/{id}/actions")
    JsonNode action(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key, @RequestBody BookingService.Action request) {
        return bookings.action(principal.accountId(), id, key, request);
    }

    @PostMapping("/bookings/{id}/reschedules")
    ResponseEntity<JsonNode> propose(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key,
            @RequestBody BookingService.RescheduleRequest request) {
        JsonNode proposal = bookings.propose(principal.accountId(), id, key, request);
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + id)).body(proposal);
    }

    @PostMapping("/bookings/{id}/reschedules/{rid}/decision")
    JsonNode decide(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id, @PathVariable UUID rid,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key,
            @RequestBody BookingService.RescheduleDecision request) {
        return bookings.decide(principal.accountId(), id, rid, key, request);
    }

    @GetMapping("/me/notifications")
    BookingService.Page<BookingService.Notice> notices(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int limit) {
        return bookings.notices(principal.accountId(), cursor, limit);
    }

    record CalendarVisibility(String visibility) {}
}
