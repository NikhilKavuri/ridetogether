# RideTogether

Live location sharing built for group motorbike rides, because Google Maps
cannot show two riders each other on one screen.

Native Android, Kotlin + Jetpack Compose, minSdk 26 (Android 8.0).

**Start here: [SETUP.md](SETUP.md).** Nothing runs until the keys are in place.

---

## Phase 1 — what is in this build

| | |
|---|---|
| Destination search | Google Places autocomplete, biased to your position, India-restricted |
| Ride rooms | Six-character code, share sheet, deep link that pre-fills the code |
| Riders as characters | Eight distinct animals, assigned so no two riders in a ride collide |
| Live location | Both riders on one map, straight-line distance and compass bearing between them |
| Break requests | One button tells the whole group, with a high-priority notification that lands even with the screen off |
| Offline behaviour | Cached rides, queued writes, an explicit "reconnecting" state, and stale markers that admit they are stale |

Phase 2 is the AI work — reroute on a closed road, constrained route building
("NH only, via Pune, not Solapur"), and hotel/food/viewpoint marking along the
route. It needs the Routes API and a Groq key, and it is not in this build.

---

## How it is put together

```
MainActivity ──► MainViewModel ──┐
                                 ├──► RideRepository ──► Firebase RTDB
RideScreen   ──► RideViewModel ──┘         ▲
                                           └── FakeRideRepository (tests)
LocationService ─────────────────────────────┘
```

`RideRepository` is the only interface in the codebase and it exists for one
reason: it lets the whole ride flow — two riders, distances, break alerts,
someone's phone dying — be tested on a laptop in two seconds instead of needing
an emulator, a Firebase project and a friend on a second motorbike.

Some choices worth knowing about, all of them in service of "runs on a cheap
phone without lagging":

- **Realtime Database, not Firestore.** The app's whole job is pushing a small
  value often and fanning it out fast. That is RTDB's shape, at a fraction of
  the per-write cost, and it fits in the free Spark plan.
- **`LocationThrottle` decides what this costs to run.** The fused provider
  offers a fix a second; publishing all of them would burn battery, data and
  quota so your friend's marker could move three metres. Roughly one write per
  4–5 s while moving, one per 25 s while parked. `LocationThrottleTest` pins
  both ends down, including an hour of simulated highway riding.
- **No dependency injection framework, no navigation library.** Three
  singletons and two screens. Hilt would add an annotation processor and twenty
  seconds a build to replace fifteen lines; Navigation Compose would add route
  strings and argument serialisation to replace a sealed interface.
- **Nothing is deserialised reflectively.** `Snapshots.kt` reads every Firebase
  field explicitly, so R8 renaming fields in the release build cannot silently
  turn your data into nulls — the classic Firebase-in-production failure.
- **Marker bitmaps are cached.** The ride screen recomposes every time anyone
  moves; rasterising eight marker bitmaps each time would be visible on a
  low-end phone.
- **A committed keystore.** So the signing SHA-1 that the Maps key and Firebase
  are locked to never changes, wherever the build runs.

---

## Testing

```bash
./gradlew testDebugUnitTest          # JVM suite, seconds
./gradlew connectedDebugAndroidTest  # on a device or emulator
```

CI runs the JVM suite on every push, then the on-device suite on emulators at
API 26 and API 34 — oldest supported and current. Reports upload as artifacts
even when a run fails.

The JVM suite covers the geometry, the ride codes, the throttle, and the whole
ride state machine including two-rider scenarios via `FakeRideRepository`. The
on-device suite covers the home screen interactions and a launch smoke test
that boots the real app.

What automation cannot cover is two phones, a highway and patchy signal.
[TESTPLAN.md](TESTPLAN.md) is the manual pass for that, and it is worth doing
once in a car park before doing it at 80 km/h.

---

## Known limits

- Distance between riders is straight-line, not road distance. Road distance
  costs a Routes API call per update; the number that matters mid-ride is "how
  far back are they", and on a shared route the two barely differ.
- Background location is "while using the app" plus a foreground service.
  Always-on background location would need a Play Store review the app does not
  need, since it is sideloaded.
- Eight riders maximum, one per character.
