# Persistent APK signing

CI and tag releases use the same repository secrets:

| Secret | Value |
| --- | --- |
| `LIVE_CAPTIONS_KEYSTORE_B64` | Base64 of the existing release keystore |
| `LIVE_CAPTIONS_STORE_PASSWORD` | Keystore password |
| `LIVE_CAPTIONS_KEY_PASSWORD` | Password for alias `live-captions` |

All three are already configured for `Genevrier/live-captions`. No new key or
secret setup is required. The existing backup is outside the checkout at
`~/.local/share/live-captions-signing/release.jks`; keep an additional secure backup
of that key and its passwords. GitHub does not let you retrieve secret values.

The public certificate SHA-256 is pinned in `scripts/release-certificate.sha256`:

```text
22969206d9b11fef5f598b9eaaa5b65f37e48da39003f1c8f7ee26111373b45b
```

Both workflows fail if secrets are absent, the key is invalid, or the signing
identity changes. They decode the key into a private runner temporary file,
remove it in an `always()` cleanup step, and verify the signed APK before upload.
Neither the keystore nor passwords belong in Git or uploaded artifacts.

## Restore missing secrets

Use the **existing** keystore. Generating a replacement would break updates for
installed APKs signed by the original identity. Run these Bash commands on your
own machine; password input is hidden and values are passed on standard input,
not command-line arguments. Disable shell tracing first.

```bash
set +x
release_keystore="$HOME/.local/share/live-captions-signing/release.jks"
test -f "$release_keystore"
base64 < "$release_keystore" | gh secret set LIVE_CAPTIONS_KEYSTORE_B64 --repo Genevrier/live-captions
read -r -s -p 'Keystore password: ' signing_store_password
printf '\n'
printf '%s' "$signing_store_password" | gh secret set LIVE_CAPTIONS_STORE_PASSWORD --repo Genevrier/live-captions
unset signing_store_password
read -r -s -p 'Password for alias live-captions: ' signing_key_password
printf '\n'
printf '%s' "$signing_key_password" | gh secret set LIVE_CAPTIONS_KEY_PASSWORD --repo Genevrier/live-captions
unset signing_key_password
gh secret list --repo Genevrier/live-captions
gh workflow run ci.yml --repo Genevrier/live-captions --ref main
```

Do not rotate the key or change the pinned certificate as part of routine builds.
Fork pull requests do not receive these secrets and cannot produce a signed
release with this workflow.

## Update compatibility and artifact names

Keep `applicationId = "com.asr.live"` and this signing key. Increase `versionCode`
in `app/build.gradle.kts` for each new app release; this delivery uses **6**, above
the previous delivery's **5**. Keep increasing it even if changing `versionName`.
Rebuilding the same release retains its version code. APKs previously signed
with an unrelated debug key cannot be updated using this identity.

The final filename is `LiveTranslate-MagicV5.apk`; the signed manifest contains
versionName 2.3 and versionCode 6. Both workflows upload the APK and its SHA-256 together;
the tag workflow also attaches them to the GitHub Release. The checksum uses a
relative filename so, after extraction, verification is simply:

```bash
sha256sum -c LiveTranslate-MagicV5.apk.sha256
```

See [Android signing](https://developer.android.com/studio/publish/app-signing),
[Android versioning](https://developer.android.com/studio/publish/versioning), and
[GitHub secret setup](https://cli.github.com/manual/gh_secret_set).
