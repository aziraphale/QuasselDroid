#!/bin/bash

# Rebuild QuasselDroid APKs
echo "Executing gradle..."
./gradlew assembleDebug

# Copy built APK to project root for easy uploading
APK_SOURCE="QuasselDroid/build/outputs/apk/QuasselDroid-debug.apk"
GIT_HASH=`git show --abbrev=8 --format=format:"%h"`
DATE_STR=`date +"%Y%m%d_%H%M"`
OUT_FILENAME="QuasselDroid_"$GIT_HASH"_"$DATE_STR".apk"

echo
echo "Copying the built APK to the project root directory..."
cp -v "$APK_SOURCE" "$OUT_FILENAME"
