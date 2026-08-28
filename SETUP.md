# Setup

Two accounts to configure, then one push. About 25 minutes end to end.

Everything below stays inside permanently-free quotas for a ride group of your
size. Step 4 puts hard caps in place so that stays true even if something in
the app misbehaves.

---

## The one number you need throughout

The app is signed by a keystore committed at `keystore/ridetogether.keystore`,
so every build — yours, mine, GitHub's — produces the same fingerprint:

```
Package name:  com.nikhil.ridetogether
SHA-1:         41:06:BD:CF:E5:C8:9A:9E:B1:13:6F:3B:DD:C1:CB:89:DF:A7:8A:F4
```

Both Google Cloud and Firebase ask for this pair. A per-machine debug keystore
would change the SHA-1 on every CI runner and silently break the map, which is
the whole reason the keystore is in the repo. The password is `ridetogether`
and protects nothing — this key exists to keep a fingerprint stable, not to
guard a Play Store listing.

---

## 1. Google Cloud project

1. <https://console.cloud.google.com> → new project, call it `ridetogether`.
2. Billing → link a card. **This does not mean you pay.** Google requires a
   billing account before it will switch the Maps APIs on at all; the free
   allowances below are what you actually consume.
3. APIs & Services → Library → enable exactly these two:
   - **Maps SDK for Android**
   - **Places API (New)**

   Enable nothing else. Every extra API is another way to be surprised.

### What each one costs you

| What the app does | SKU | Free every month | You'll use |
|---|---|---|---|
| Showing the map | Maps SDK for Android | **Unlimited** | unlimited |
| Destination suggestions | Autocomplete Session Usage | **Unlimited** | unlimited |
| Coordinates for the chosen place | Place Details Essentials | 10,000 | ~20 |

Map display in a native Android app is free with no cap at all. Autocomplete is
billed per *session* rather than per keystroke, and session usage is also
uncapped — `PlacesSearch.kt` shares one session token across the keystrokes and
the follow-up details fetch, which is what keeps you in that bucket instead of
being charged for every letter typed.

Realistically: a few dozen billable calls a month against a 10,000 allowance.

---

## 2. API key

1. APIs & Services → Credentials → **Create credentials → API key**.
2. Edit the new key:
   - **Application restrictions** → *Android apps* → Add → paste the package
     name and SHA-1 from the top of this file.
   - **API restrictions** → *Restrict key* → tick **Maps SDK for Android** and
     **Places API (New)**.
3. Copy the key.

The Android restriction is what makes it safe for this key to ship inside the
APK: extracted from the APK it is useless, because Google checks the calling
app's package and signature on every request.

---

## 3. Firebase project

1. <https://console.firebase.google.com> → **Add project** → pick the *same*
   Google Cloud project you made in step 1.
2. Add an **Android** app:
   - package name `com.nikhil.ridetogether`
   - SHA-1 from the top of this file
3. Download `google-services.json` and drop it in over `app/google-services.json`.
4. **Build → Realtime Database → Create database.**
   Pick `asia-southeast1` (Singapore) — closest region to India, roughly 40 ms
   better round trip than the US ones, which is the latency between your friend
   moving and your screen showing it. Start in **locked mode**.
5. Rules tab → replace the contents with `database.rules.json` from this repo →
   Publish. These scope every write to the rider making it: nobody can move
   your marker or leave the ride on your behalf.
6. **Build → Authentication → Get started → Sign-in method → Anonymous → Enable.**

   Anonymous auth is deliberate. Nobody wants an account to go on a ride, and
   the app needs no identity beyond "the same phone as last time" — which is
   what an anonymous uid is. Stays on the free Spark plan; no card here.

---

## 4. Cap the quotas so a bug cannot bill you

APIs & Services → **Quotas & System Limits** → filter to each enabled API and
set a daily cap:

| API | Suggested daily cap |
|---|---|
| Places API (New) | 200 requests/day |
| Maps SDK for Android | leave alone (free, uncapped) |

Then Billing → **Budgets & alerts** → budget of ₹100 with an alert at 50%.

With the cap set, a runaway loop stops working rather than spending money. The
alert is the backstop for anything the cap does not cover. Do this before you
first run the app, not after.

---

## 5. Build the APK

### On GitHub (nothing to install)

1. Create a private repo, then from this folder:

   ```bash
   git init && git add -A && git commit -m "RideTogether phase 1"
   git branch -M main
   git remote add origin git@github.com:NikhilKavuri/ride-together.git
   git push -u origin main
   ```

2. Repo → Settings → Secrets and variables → Actions → **New repository secret**:

   | Name | Value |
   |---|---|
   | `MAPS_API_KEY` | the key from step 2 |
   | `GOOGLE_SERVICES_JSON` | `base64 -w0 app/google-services.json` (on Windows: `certutil -encode`, then strip the header/footer lines and newlines) |

3. Actions tab → the run that just started → when it finishes, download
   **ridetogether-apks**. Install `app-release.apk` on both phones.

Push the secrets before the first run, or you get an APK that boots into the
setup screen. That is by design — see `SetupGate.kt` — but it wastes a run.

### Locally in Android Studio

Create `local.properties` in the project root:

```properties
MAPS_API_KEY=your_key_here
```

Then Run. `local.properties` is gitignored, so the key stays off GitHub.

---

## Checking it worked

Install on both phones, then:

1. You: enter a name → **Start a ride** → note the six-character code.
2. Share it (the share button sends a link that opens the app with the code
   already filled in).
3. Friend: same app, enter name, type the code, **Join ride**.
4. Both allow location "while using the app".

Within about ten seconds each of you should see the other as a character on the
map with a distance underneath. `TESTPLAN.md` has the full checklist, including
the cases worth walking through before you rely on this on an actual highway.

## When something is wrong

| Symptom | Cause |
|---|---|
| "Setup incomplete" screen | Exactly what it says; it names the file to fix |
| Grey map, everything else works | Key restriction mismatch — re-check the SHA-1 and that **Maps SDK for Android** is ticked under API restrictions |
| Search returns nothing | **Places API (New)** not enabled, or not ticked on the key |
| Ride code "not found" on the other phone | Realtime Database not created, or rules not published |
| Friend's marker frozen | Their phone lost signal; the marker goes faded and shows "stale" rather than lying to you |
