#!/bin/bash
set -e

echo "🚀 Building self-contained native ARM64 (armv8-a) bundle..."

DOCKER_BUILDKIT=1 docker build \
  --platform linux/arm64 \
  --file Dockerfile.arm64 \
  --output type=local,dest=. \
  .

echo "✅ Successfully built: instastorysaver-linux-arm64/"
echo "👉 Run directly on any Linux ARM64 system:"
echo "   ./instastorysaver-linux-arm64/instastorysaver --destination-folder /path/to/stories ..."
