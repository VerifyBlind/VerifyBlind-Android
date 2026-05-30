#!/usr/bin/env bash
set -e

APK_PATH=$1
TEMP_DIR="temp_apk"

if [ -z "$APK_PATH" ]; then
    echo "Usage: ./add_code_transparency.sh <path_to_apk>"
    exit 1
fi

echo "🛡️ Extracting APK for Code Transparency analysis..."
rm -rf "$TEMP_DIR"
unzip -q -o "$APK_PATH" -d "$TEMP_DIR"

cd "$TEMP_DIR"

# Create assets folder if it doesn't exist
mkdir -p assets
MANIFEST_JSON="assets/code_transparency.json"

echo "{" > "$MANIFEST_JSON"
echo "  \"dex_hashes\": {" >> "$MANIFEST_JSON"

FIRST=1
# Find all dex files in the root of the APK, sort alphabetically for determinism
for dex in $(find . -maxdepth 1 -name "*.dex" | sort); do
    HASH=$(sha256sum "$dex" | awk '{print $1}')
    FILENAME=$(basename "$dex")
    if [ $FIRST -eq 1 ]; then
        FIRST=0
    else
        echo "," >> "$MANIFEST_JSON"
    fi
    echo -n "    \"$FILENAME\": \"$HASH\"" >> "$MANIFEST_JSON"
done

echo "" >> "$MANIFEST_JSON"
echo "  }," >> "$MANIFEST_JSON"
# Use determinism for the timestamp (if SOURCE_DATE_EPOCH exists)
EPOCH=${SOURCE_DATE_EPOCH:-$(date +%s)}
ISO_DATE=$(date -u -d @"$EPOCH" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$EPOCH" +%Y-%m-%dT%H:%M:%SZ)
echo "  \"transparency_timestamp\": \"$ISO_DATE\"" >> "$MANIFEST_JSON"
echo "}" >> "$MANIFEST_JSON"

echo "Code Transparency Manifest created:"
cat "$MANIFEST_JSON"

# Generate a demonstrative RSA key for signing the manifest if one doesn't exist.
# Ed25519 is great but some runners run old OpenSSL versions and Android verification 
# is more standard with RSA.
TEST_KEY="test_transparency_private.pem"
if [ ! -f "$TEST_KEY" ]; then
    # Create a 2048-bit RSA key
    openssl genrsa -out "$TEST_KEY" 2048 2>/dev/null
    echo "Generated new ephemeral RSA key for Code Transparency signature."
fi

# Extract public key for verification
openssl rsa -pubout -in "$TEST_KEY" -out assets/code_transparency_public.pem

# Sign the manifest using SHA-256
openssl dgst -sha256 -sign "$TEST_KEY" -out assets/code_transparency.sig "$MANIFEST_JSON"
# Base64 encode the signature for easier parsing in Android
base64 assets/code_transparency.sig > assets/code_transparency_sig.b64
rm -f assets/code_transparency.sig "$TEST_KEY"

# Prepare to zip back but deterministically
TIME_FORMAT=$(date -u -d @"$EPOCH" +%Y%m%d%H%M 2>/dev/null || date -u -r "$EPOCH" +%Y%m%d%H%M) # support GNU date or Fallback BSD
find . -exec touch -t "$TIME_FORMAT" -a -m {} +

rm -f ../app-transparency.zip

echo "Repacking deterministically..."
# Create uncompressed zip deterministically (sorted)
find . -type f | sort | zip -q -X -0 ../app-transparency.zip -@

cd ..
mv app-transparency.zip "$APK_PATH"
rm -rf "$TEMP_DIR"

echo "✅ Code Transparency correctly injected into $APK_PATH."
