#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/sdk/build-tools/35.0.0" "$TMP/apks"
cat > "$TMP/sdk/build-tools/35.0.0/apksigner" <<'SIGN'
#!/usr/bin/env bash
set -euo pipefail
apk=${@: -1}
case "$(basename "$apk")" in
  companion-mismatch.apk) digest=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ;;
  *) digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ;;
esac
printf 'Verifies\nSigner #1 certificate SHA-256 digest: %s\n' "$digest"
SIGN
chmod +x "$TMP/sdk/build-tools/35.0.0/apksigner"
for name in host companion fixture64 fixture32 companion-mismatch; do
  printf '%s\n' "$name" > "$TMP/apks/$name.apk"
done
ANDROID_SDK_ROOT="$TMP/sdk" python3 "$ROOT/tools/release_apk_signing.py" verify \
  --host "$TMP/apks/host.apk" --companion "$TMP/apks/companion.apk" \
  --fixture64 "$TMP/apks/fixture64.apk" --fixture32 "$TMP/apks/fixture32.apk"
if ANDROID_SDK_ROOT="$TMP/sdk" python3 "$ROOT/tools/release_apk_signing.py" verify \
  --host "$TMP/apks/host.apk" --companion "$TMP/apks/companion-mismatch.apk" \
  --fixture64 "$TMP/apks/fixture64.apk" --fixture32 "$TMP/apks/fixture32.apk"; then
  echo 'Mismatch signer unexpectedly passed' >&2
  exit 1
fi
printf 'PASS release APK signing verifier self-test\n'
