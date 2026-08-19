#!/usr/bin/env bash
# 发版脚本:自动递增版本号并发布到 GitHub
# 用法: ./scripts/release.sh [commit描述]
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE=app/build.gradle.kts
FILE=app/build/outputs/apk/release/app-release.apk

VCODE=$(grep -oP '(?<=versionCode = )\d+' "$GRADLE")
VNAME=$(grep -oP '(?<=versionName = ")[^"]+' "$GRADLE")

# versionName 形如 0.0.0.x,末段 +1
IFS='.' read -r -a PARTS <<< "$VNAME"
NEW_LAST=$(( ${PARTS[-1]:-0} + 1 ))
NEW_NAME="${PARTS[*]:0:${#PARTS[@]}-1}.$NEW_LAST"
NEW_CODE=$(( VCODE + 1 ))

sed -i "s/versionCode = $VCODE/versionCode = $NEW_CODE/; s/versionName = \"$VNAME\"/versionName = \"$NEW_NAME\"/" "$GRADLE"
echo "版本: $VNAME($VCODE) -> $NEW_NAME($NEW_CODE)"

./gradlew assembleDebug assembleRelease 2>&1 | grep -E "BUILD|error" | head -5

MSG="${1:-build: 发布 v$NEW_NAME}"

git add -A
git commit -m "$MSG" -m "versionCode $VCODE -> $NEW_CODE, versionName $VNAME -> $NEW_NAME"
git push origin main

# release 未配置签名时产物为 unsigned,优先 release 正式包
if [ -f "$FILE" ]; then
    APK="$FILE"
elif [ -f "${FILE%.apk}-unsigned.apk" ]; then
    APK="${FILE%.apk}-unsigned.apk"
else
    APK=app/build/outputs/apk/debug/app-debug.apk
fi
echo "已发布: $NEW_NAME ($NEW_CODE)"
echo "APK: $APK"