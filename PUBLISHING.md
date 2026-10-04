# Publishing Minimal Retro Launcher on Google Play

The app side is done: package `com.swasim.minretrolauncher`. The open-source
build has no in-app purchases: the paid features of the Play build are shown
behind a `<PAID>` banner and are not implemented. What follows is what only you
can do, in order. Step 3 is the only one that touches this repo.

> **Never lose `upload.jks` or its passwords.** Back them up somewhere that is
> not this repo, e.g. a password manager. Google can reset a lost upload key,
> but only through support, and it takes days.

---

### 1. Developer account (once)
- Sign up at **play.google.com/console**. It costs a one-time **US$25** and
  requires identity verification.
- **Personal accounts** must run a **closed test with at least 12 testers for
  14 days** before production is unlocked (step 6). Start recruiting testers now.

### 2. Create the app
**Home → Create app**:
- Name `Minimal Retro Launcher`
- Type **App**
- Pricing **Free**. A paid app can never be made free later.

### 3. Upload key → GitHub Secrets
```bash
keytool -genkeypair -v -keystore upload.jks -alias upload \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 upload.jks > upload.b64        # macOS: base64 -i upload.jks -o upload.b64
```
In the repo, go to **Settings → Secrets and variables → Actions → New repository
secret** and add:

| Secret | Value |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | contents of `upload.b64` |
| `UPLOAD_STORE_PASSWORD` | the keystore password |
| `UPLOAD_KEY_ALIAS` | `upload` |
| `UPLOAD_KEY_PASSWORD` | the key password (same as above unless you chose otherwise) |

Then delete `upload.b64`. Keep `upload.jks` backed up and **out of git**.

### 4. First build → Internal testing
- Push any commit. CI now attaches **`app-release.aab`** to the build's GitHub
  Release. Without the secrets, it prints a notice and skips only this file.
- Play Console → **Test and release → Internal testing → Create release**:
  - accept **Play App Signing** (Google holds the distribution key; yours only
    uploads)
  - upload the `.aab`
  - roll out
- `versionCode` comes from the CI run number, so the newest CI build is always
  the one to upload.

### 5. Store listing and App content
**Grow users → Store presence → Main store listing**:
- icon **`store/icon-512.png`**
- feature graphic **`store/feature-graphic.png`**
- **2–8 phone screenshots**, which you take yourself
- short description (≤80 chars) and full description

**Policy and programs → App content**, fill in every section:
- **Privacy policy**: host `PRIVACY.md` at any **public** URL. This repo is
  private, so a public Gist or Google Sites page works. Fill in its two
  placeholders first.
- **Data safety**:
  - Location (approximate + precise) is *collected*, for *app functionality*;
    it is *not shared*, *processed ephemerally*, and *optional*.
  - Nothing else is collected by the app.
- **Accessibility API declaration**: say it is used only to lock the screen
  (double-tap) and open the notification shade (swipe down), and that no data
  is read. Google asks for a **short screen recording** that shows the
  in-app disclosure (*Settings → PERMISSIONS → NOTIFICATION SHADE*), then
  both actions.
- Ads: **No**. Content rating questionnaire. Target audience: **13+** or
  older, never children.

### 6. Protection, closed test, production
- **Test and release → App integrity → Automatic protection**: turn it on if
  your app is offered it. It is the one anti-tamper measure available without
  a backend: it makes a modified, re-signed copy refuse to run.
- *(Personal accounts only)* **Closed testing**: at least 12 testers, opted in,
  for 14 days. Then **Apply for production**.
- **Production → Create release** with the latest CI `.aab`. A staged rollout
  (e.g. 20%) is the safe default.

### Every update after that
Push, wait for CI, download `app-release.aab` from the GitHub Release, and
upload it as a new production release.
