# Test plan

Automated coverage is in CI; this file is the part that needs two phones.

Do the smoke pass in a car park before relying on any of it on a highway. The
regression pass is what to re-run after a change.

---

## Automated (runs in CI on every push)

| Suite | Where | Covers |
|---|---|---|
| `GeoTest` | JVM | Distances checked against an independent haversine implementation, bearings, formatting, staleness in both clock directions |
| `RideCodeTest` | JVM | 2,000 generated codes valid, confusable characters never generated and never guessed at, link round-trip |
| `CharactersTest` | JVM | First two riders never collide, freed characters reused, out-of-range ids wrap |
| `LocationThrottleTest` | JVM | First fix, minimum interval, jitter rejection, parked heartbeat, backwards clock, and a simulated highway hour landing at ~900 writes |
| `RideStateBuilderTest` | JVM | Distances, ordering, stale flags, and the cases where a distance must stay unknown rather than be invented |
| `MainViewModelTest` | JVM | Create, join, code normalisation, every error path, invite links |
| `RideViewModelTest` | JVM | Break alerts, own-event suppression, replayed-event suppression, roster distances, disconnect, connection loss |
| `HomeScreenTest` | Device | Button enablement, tap handling, error display, busy state |
| `LaunchSmokeTest` | Device | Real app boots to RESUMED without crashing |

---

## Smoke pass — 10 minutes, two phones, standing still

Do this once after setup and after any build you intend to actually ride with.

1. **Install** — release APK on both phones. App opens to the home screen, not
   the setup screen. *(Setup screen = keys missing; fix that first.)*
2. **Create** — phone A: name, Start a ride. A six-character code appears.
3. **Invite** — phone A: share button → WhatsApp → send to phone B.
4. **Join by link** — phone B: tap the link. App opens with the code already
   filled in. Enter a name, Join ride.
5. **Permissions** — both phones: allow location. The red permission card in the
   bottom panel disappears.
6. **See each other** — within ~10 s each phone shows two characters on the map
   and a distance in the roster. Standing together, expect < 20 m.
7. **Distance changes** — walk 50 m apart. Both distances update within ~5 s and
   roughly agree.
8. **Break request** — phone A: "Tell everyone I need a break". Phone B shows a
   red banner *and* a notification. Phone A's roster marks A as ON BREAK.
9. **Break with the screen off** — lock phone B, repeat from phone A. Phone B
   buzzes and shows the notification on the lock screen. *This is the case the
   app exists for; do not skip it.*
10. **Resume** — phone A: "Ready to ride". Phone B is told, ON BREAK clears.
11. **Search** — either phone: tap Destination, type a town name. Suggestions
    appear within a second, nearest first. Pick one; a pin lands on the map and
    the other phone sees the same destination.
12. **Leave** — phone A: X → leaves the ride, the tracking notification
    disappears. Confirm it is gone from the shade — a location service still
    running after you left is a battery bug.

## Regression pass — after any change

Everything in the smoke pass, plus:

| Case | Expected |
|---|---|
| Airplane mode on phone B for 60 s | Phone A shows B's marker faded with "stale"; A's own header shows "Offline — reconnecting" only if A also lost signal |
| Airplane mode off | B's marker recovers within ~25 s without an app restart |
| Force-stop phone B | B disappears from A's map entirely, rather than freezing in place |
| Rotate the phone mid-ride | Map keeps position, riders still listed, no flicker |
| Background phone A for 5 minutes | B still sees A moving; the tracking notification stayed up |
| Revoke location permission mid-ride from Settings | Permission card returns, app does not crash |
| Join with a wrong code | "No active ride with code XXXXXX" — and it stays on the home screen |
| Join with a lowercase or hyphenated code | Accepted; normalised as you type |
| Both riders request a break at once | Both see the other's banner; neither overwrites the other |
| Kill and reopen the app mid-ride | Break state survives — you are still marked ON BREAK if you were |

## Battery and heat — worth one real ride

Charge both to 100%, ride an hour, check:

- Battery drop: expect roughly 12–18%/hour with the screen on and navigation
  running. Much more means the throttle is not doing its job.
- Phone temperature: warm is normal, too hot to hold is not.
- Data: expect well under 5 MB an hour.
- Settings → Battery → app usage: RideTogether should not be the top consumer;
  the screen should be.

## What is deliberately not tested

- Route accuracy — phase 1 draws no route.
- More than four riders at once — the code supports eight, but it has not been
  exercised beyond four.
- Tunnels and multi-kilometre dead zones — the stale marker and the queued
  writes handle it in principle, and neither has been proven on a real one.
