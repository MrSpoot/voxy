#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES_FILE="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$PROPERTIES_FILE" ]; then
  echo "Missing $PROPERTIES_FILE" >&2
  exit 1
fi

DISTRIBUTION_URL=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES_FILE" | head -n 1)
if [ -z "$DISTRIBUTION_URL" ]; then
  echo "distributionUrl is not configured in $PROPERTIES_FILE" >&2
  exit 1
fi

MAVEN_USER_HOME=${MAVEN_USER_HOME:-"$HOME/.m2"}
ARCHIVE_NAME=$(basename "$DISTRIBUTION_URL")
ARCHIVE_NAME=${ARCHIVE_NAME%%\?*}
MAVEN_DIR_NAME=${ARCHIVE_NAME%-bin.zip}
MAVEN_DIR_NAME=${MAVEN_DIR_NAME%-bin.tar.gz}
INSTALL_DIR="$MAVEN_USER_HOME/wrapper/dists/$MAVEN_DIR_NAME"

if [ ! -x "$INSTALL_DIR/bin/mvn" ]; then
  mkdir -p "$MAVEN_USER_HOME/wrapper/dists"
  ARCHIVE_PATH="$MAVEN_USER_HOME/wrapper/dists/$ARCHIVE_NAME"
  EXTRACT_DIR="$MAVEN_USER_HOME/wrapper/dists/.extract-$MAVEN_DIR_NAME-$$"

  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$DISTRIBUTION_URL" -o "$ARCHIVE_PATH"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$DISTRIBUTION_URL" -O "$ARCHIVE_PATH"
  else
    echo "curl or wget is required to download Maven." >&2
    exit 1
  fi

  rm -rf "$EXTRACT_DIR"
  mkdir -p "$EXTRACT_DIR"

  case "$ARCHIVE_NAME" in
    *.zip)
      if ! command -v unzip >/dev/null 2>&1; then
        echo "unzip is required to extract $ARCHIVE_NAME." >&2
        exit 1
      fi
      unzip -q "$ARCHIVE_PATH" -d "$EXTRACT_DIR"
      ;;
    *.tar.gz)
      tar -xzf "$ARCHIVE_PATH" -C "$EXTRACT_DIR"
      ;;
    *)
      echo "Unsupported Maven archive format: $ARCHIVE_NAME" >&2
      exit 1
      ;;
  esac

  EXTRACTED_DIR=$(find "$EXTRACT_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
  if [ -z "$EXTRACTED_DIR" ]; then
    echo "Failed to locate extracted Maven distribution." >&2
    exit 1
  fi

  rm -rf "$INSTALL_DIR"
  mv "$EXTRACTED_DIR" "$INSTALL_DIR"
  rm -rf "$EXTRACT_DIR" "$ARCHIVE_PATH"
fi

exec "$INSTALL_DIR/bin/mvn" "$@"
