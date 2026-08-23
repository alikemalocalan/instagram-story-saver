#!/bin/bash
set -e

echo "📦 1/2 Building Fat JAR with sbt..."
sbt assembly
JAR_PATH=$(find target -type f -name "instastorysaver.jar" | head -n 1)
cp -f "$JAR_PATH" ./instastorysaver.jar

echo "🚀 2/2 Building native ARM64 bundle with Docker..."
DOCKER_BUILDKIT=1 docker build \
  --platform linux/arm64 \
  --file Dockerfile.arm64 \
  --output type=local,dest=. \
  .

echo "✅ Successfully built: instastorysaver-linux-arm64/"
echo "👉 Run directly on any Linux ARM64 system:"
echo "   ./instastorysaver-linux-arm64/instastorysaver --destination-folder /path/to/stories ..."
