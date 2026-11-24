"""Exercise booking invariants against a running local API and an isolated Docker database."""

import argparse
import concurrent.futures
import datetime as dt
import json
import pathlib
import re
import subprocess
import time as clock
import urllib.error
import urllib.parse
import urllib.request
import uuid


def run(args):
    root = f"http://127.0.0.1:{args.port}/api/v1"
    workspace = pathlib.Path(__file__).resolve().parents[2]

    def api(method, path, payload=None, token=None, key=None, expected=200):
        headers = {}
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(root + path, method=method, headers=headers,
                                         data=None if payload is None else json.dumps(payload).encode("utf-8"))
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read()
            result = json.loads(body) if body else None
            if expected is None:
                return response.status, result
            assert response.status == expected, (method, path, response.status, result)
            return result

    def sql(statement):
        command = ["docker", "compose", "exec", "-T", "db", "psql", "-U", "photograph",
                   "-d", args.database, "-v", "ON_ERROR_STOP=1", "-qtA", "-c", statement]
        return subprocess.check_output(command, cwd=workspace, text=True).strip()

    def login(number):
        challenge = api("POST", "/auth/otp/challenges", {"phone": number}, expected=201)["challengeId"]
        output = args.dev_log.read_text(encoding="utf-8", errors="replace")
        code = re.search(re.escape(challenge) + r" is ([0-9]{6})", output)
        assert code, "Expected the local SMS adapter to log this challenge"
        session = api("POST", "/auth/sessions", {"challengeId": challenge, "code": code[1]}, expected=201)
        return session["accessToken"]

    phone_prefix = "+999" + str(uuid.uuid4().int)[0:9]
    photographer, first_customer, second_customer, operator = [login(phone_prefix + str(index)) for index in range(4)]
    operator_id = str(uuid.UUID(api("GET", "/me/profile", token=operator)["id"]))
    assert sql(
        f"UPDATE photograph_app.accounts SET is_operator = true WHERE id = '{operator_id}' RETURNING id"
    ) == operator_id, "The API must use the specified test database"

    api("PATCH", "/me/profile", {"acceptsPaidBookings": True, "acceptsTfpBookings": True}, photographer)
    profile = api("POST", "/me/photographer/applications", {"city": "杭州"}, photographer, expected=201)
    profile_id = profile["id"]
    api("POST", f"/admin/photographers/{profile_id}/decision",
        {"decision": "APPROVED", "reason": "预约联调", "expectedVersion": 1}, operator, expected=204)
    package = api("POST", "/me/photographer/packages", {"name": "联调套餐", "durationMinutes": 60,
                  "retouchedCount": 9, "deliveryDays": 7, "priceMinor": 69900, "listed": True},
                  photographer, expected=201)

    style = api("GET", "/styles")[0]["id"]
    api("PUT", "/me/photographer/styles", {"styleIds": [style]}, photographer)
    directory = api("GET", f"/search/home?bookable=true&style={style}")
    assert any(item["id"] == profile_id for item in directory["items"])
    assert not api("GET", "/search/home?bookable=true&city=no-such-city")["items"]
    community_status = 404 if args.community_disabled else 200
    api("GET", "/feed", expected=community_status)
    if args.community_disabled:
        api("POST", "/posts", {"body": "首发社区应关闭", "clientPostId": str(uuid.uuid4())}, photographer, expected=404)
    else:
        post = api("POST", "/posts", {"body": "约拍社区验收", "clientPostId": str(uuid.uuid4())}, photographer, expected=201)
        assert any(item["id"] == post["id"] for item in api("GET", "/feed")["items"])
        api("DELETE", f"/me/posts/{post['id']}", token=photographer, expected=204)

    date = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date() + dt.timedelta(days=2)

    def time(hours):
        return f"{date}T{hours}:00+08:00"

    window = {"startsAt": time("09:00"), "endsAt": time("19:00")}
    api("PUT", "/me/photographer/availability",
        {"zoneId": "Asia/Shanghai", "bufferMinutes": 30, "windows": [window]}, photographer)
    calendar = f"/photographers/{profile_id}/availability?packageId={package['id']}&date={date}"
    assert api("GET", calendar) == {"visibility": "PRIVATE", "zoneId": None, "slots": []}

    def create(customer, hours, key=None, expected=201, **values):
        request = {"packageId": package["id"], "packageVersion": 1, "startsAt": time(hours), "meetingArea": "西湖区"}
        return api("POST", "/bookings", request | values, customer, key or str(uuid.uuid4()), expected)

    def act(account, booking, action, expected=200, **values):
        payload = {"action": action, "expectedVersion": booking["version"]} | values
        return api("POST", f"/bookings/{booking['id']}/actions", payload, account, str(uuid.uuid4()), expected)

    hidden = create(first_customer, "22:00")
    act(photographer, hidden, "accept", expected=409)
    act(first_customer, hidden, "withdraw")
    api("PUT", "/me/photographer/calendar-visibility", {"visibility": "PUBLIC"}, photographer)
    assert len(api("GET", calendar)["slots"]) == 18
    create(first_customer, "22:00", expected=409)
    create(first_customer, "10:00", expected=409, packageVersion=2)
    repeat_key = str(uuid.uuid4())
    first = create(first_customer, "10:00", repeat_key)
    assert create(first_customer, "10:00", repeat_key) == first
    create(first_customer, "11:00", repeat_key, expected=409)
    second = create(second_customer, "10:30")
    api("GET", f"/bookings/{first['id']}", token=second_customer, expected=404)
    act(first_customer, first, "accept", expected=403)

    by_profile = api("POST", "/conversations", {"photographerId": profile_id}, first_customer)
    by_order = api("POST", "/conversations", {"bookingId": first["id"]}, photographer)
    assert by_profile["id"] == by_order["id"]
    thread = f"/conversations/{by_profile['id']}"
    api("POST", "/conversations", {"bookingId": first["id"]}, second_customer, expected=404)
    api("GET", f"{thread}/messages", token=second_customer, expected=404)
    api("GET", f"{thread}/messages", expected=401)
    assert not api("GET", "/me/conversations", token=first_customer)["items"]
    chat = {"clientMessageId": str(uuid.uuid4()), "text": "请问可以西湖见面吗？"}
    api("POST", f"{thread}/messages", chat, second_customer, expected=404)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        sent = list(pool.map(lambda _: api("POST", f"{thread}/messages", chat, first_customer), range(2)))
    assert sent[0] == sent[1] and sent[0]["seq"] == 1
    api("POST", f"{thread}/messages", chat | {"text": "不同的消息"}, first_customer, expected=409)
    inbox = api("GET", "/me/conversations", token=photographer)["items"]
    assert inbox[0]["unreadCount"] == 1 and inbox[0]["lastText"] == chat["text"]
    answer = api("POST", f"{thread}/messages", {"clientMessageId": str(uuid.uuid4()), "text": "好的。"}, photographer)
    assert answer["seq"] == 2
    newest_chat = api("GET", f"{thread}/messages?limit=1", token=first_customer)
    assert newest_chat["items"] == [answer] and newest_chat["nextCursor"] == "2"
    assert api("GET", f"{thread}/messages?beforeSeq=2", token=first_customer)["items"] == sent[:1]
    assert api("GET", f"{thread}/messages?afterSeq=1", token=first_customer)["items"] == [answer]
    api("PUT", f"{thread}/read", {"seq": 2}, second_customer, expected=404)
    api("PUT", f"{thread}/read", {"seq": 2}, first_customer, expected=204)
    api("PUT", f"{thread}/read", {"seq": 1}, first_customer, expected=204)
    assert api("GET", "/me/conversations", token=first_customer)["items"][0]["unreadCount"] == 0
    other_thread = api("POST", "/conversations", {"bookingId": second["id"]}, second_customer)
    api("POST", f"/conversations/{other_thread['id']}/messages",
        {"clientMessageId": str(uuid.uuid4()), "text": "还可以预约吗？"}, second_customer)
    first_page = api("GET", "/me/conversations?limit=1", token=photographer)
    assert first_page["items"][0]["id"] == other_thread["id"]
    cursor = urllib.parse.urlencode({"cursor": first_page["nextCursor"], "limit": 1})
    next_page = api("GET", f"/me/conversations?{cursor}", token=photographer)
    assert next_page["items"][0]["id"] == by_profile["id"]

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        attempts = [pool.submit(act, photographer, booking, "accept", None) for booking in (first, second)]
        results = [attempt.result() for attempt in attempts]
    assert sorted(status for status, _ in results) == [200, 409], results
    winner = next(booking for status, booking in results if status == 200)
    if winner["id"] == first["id"]:
        confirmed_customer, other_customer, closed = first_customer, second_customer, second
    else:
        confirmed_customer, other_customer, closed = second_customer, first_customer, first
    assert api("GET", f"/bookings/{closed['id']}", token=other_customer)["status"] == "CONFLICT_CLOSED"
    assert len(api("GET", calendar)["slots"]) < 18

    path = f"/bookings/{winner['id']}"
    proposal = api("POST", path + "/reschedules", {"startsAt": time("14:00"), "expectedVersion": winner["version"]},
                   confirmed_customer, str(uuid.uuid4()), 201)
    old = api("GET", path, token=confirmed_customer)
    decision_path = f"{path}/reschedules/{proposal['id']}/decision"
    decision = {"decision": "accept", "expectedVersion": old["version"]}
    api("POST", decision_path, decision, confirmed_customer, str(uuid.uuid4()), 403)
    blocker = act(photographer, create(other_customer, "14:00", engagementType="TFP"), "accept")
    assert blocker["packageSnapshot"]["priceMinor"] == 0 and blocker["settlementMode"] == "NO_PAYMENT"
    api("POST", decision_path, decision, photographer, str(uuid.uuid4()), 409)
    unchanged = api("GET", path, token=photographer)
    assert unchanged["startsAt"] == winner["startsAt"] and unchanged["version"] == old["version"]
    act(other_customer, blocker, "cancel", reasonCode="SCHEDULE")
    moved = api("POST", decision_path, decision, photographer, str(uuid.uuid4()))
    assert moved["startsAt"].startswith(f"{date}T14:00")
    notices = api("GET", "/me/notifications", token=confirmed_customer)["items"]
    assert any(notice["eventType"] == "RESCHEDULE_ACCEPTED" for notice in notices)

    winner_id = str(uuid.UUID(winner["id"]))
    sql("UPDATE photograph_app.bookings SET starts_at = now() - interval '3 hours', "
        "ends_at = now() - interval '2 hours', busy_until = now() - interval '90 minutes' "
        f"WHERE id = '{winner_id}'")
    single_confirmation = act(photographer, moved, "confirmShoot")
    assert single_confirmation["status"] == "CONFIRMED" and single_confirmation["photographerConfirmed"]
    act(photographer, single_confirmation, "markDelivered", expected=409)
    awaiting = act(confirmed_customer, single_confirmation, "confirmShoot")
    assert awaiting["status"] == "AWAITING_DELIVERY"
    act(confirmed_customer, awaiting, "markDelivered", expected=403)
    act(confirmed_customer, awaiting, "confirmReceipt", expected=409)
    act(photographer, single_confirmation, "markDelivered", expected=409)

    delivery = {"action": "markDelivered", "expectedVersion": awaiting["version"]}
    delivery_key = str(uuid.uuid4())
    delivered = api("POST", f"{path}/actions", delivery, photographer, delivery_key)
    assert delivered["status"] == "DELIVERED" and delivered["deliveredAt"] and not delivered["completedAt"]
    assert api("POST", f"{path}/actions", delivery, photographer, delivery_key) == delivered
    act(photographer, delivered, "confirmReceipt", expected=403)
    completed = act(confirmed_customer, delivered, "confirmReceipt")
    assert completed["status"] == "COMPLETED" and completed["completedAt"]
    assert completed["deliveredAt"] == delivered["deliveredAt"]
    act(confirmed_customer, completed, "confirmReceipt", expected=409)
    for participant in (photographer, confirmed_customer):
        completed_items = api("GET", "/me/bookings?completed=true", token=participant)["items"]
        assert any(item["id"] == winner_id for item in completed_items)
        open_items = api("GET", "/me/bookings?completed=false", token=participant)["items"]
        assert all(item["id"] != winner_id for item in open_items)
    for participant, event in ((confirmed_customer, "BOOKING_DELIVERED"), (photographer, "BOOKING_COMPLETED")):
        items = api("GET", "/me/notifications", token=participant)["items"]
        assert sum(item["eventType"] == event and item["bookingId"] == winner_id for item in items) == 1

    expiring = create(other_customer, "16:00")
    api("DELETE", "/auth/account", token=other_customer, expected=409)
    api("DELETE", "/auth/account", token=photographer, expected=409)
    api("DELETE", "/auth/account", token=operator, expected=403)
    expiring_id = str(uuid.UUID(expiring["id"]))
    sql(f"UPDATE photograph_app.bookings SET expires_at = now() - interval '1 minute' WHERE id = '{expiring_id}'")
    act(photographer, expiring, "accept", expected=409)
    deadline = clock.monotonic() + 40
    while api("GET", f"/bookings/{expiring_id}", token=other_customer)["status"] != "EXPIRED":
        if clock.monotonic() >= deadline:
            raise AssertionError("The expiration job did not close the pending application")
        clock.sleep(1)
    photographer_account = api("GET", "/me/profile", token=photographer)["id"]
    api("DELETE", "/auth/account", token=photographer, expected=204)
    api("GET", "/me/profile", token=photographer, expected=401)
    api("GET", f"/photographers/{profile_id}/packages", expected=404)
    assert all(item["id"] != profile_id for item in api("GET", "/search/home?bookable=true")["items"])
    assert api("GET", path, token=confirmed_customer)["status"] == "COMPLETED"
    assert sql(f"SELECT phone IS NULL FROM photograph_app.accounts WHERE id = '{photographer_account}'") == "t"
    assert api("GET", "/me/conversations", token=first_customer)["items"][0]["peerClosed"]
    api("POST", f"{thread}/messages", {"clientMessageId": str(uuid.uuid4()), "text": "还在吗？"}, first_customer, expected=409)
    new_account = login(phone_prefix + "0")
    assert api("GET", "/me/profile", token=new_account)["id"] != photographer_account
    print("Booking, directory, community, conversations and account closure smoke checks passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default=8080, type=int)
    parser.add_argument("--community-disabled", action="store_true")
    parser.add_argument("--database", required=True)
    parser.add_argument("--dev-log", required=True, type=pathlib.Path)
    arguments = parser.parse_args()
    if not re.fullmatch(r"photograph_check_[a-z0-9]+", arguments.database):
        parser.error("--database must identify an isolated database named photograph_check_<suffix>")
    if not arguments.dev_log.is_file():
        parser.error("--dev-log must be the output file of the local API")
    run(arguments)
