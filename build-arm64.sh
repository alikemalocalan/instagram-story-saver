#!/bin/bash
set -e

echo "📦 1/2 Building Fat JAR with sbt..."
sbt assembly
cp target/out/jvm/scala-3.3.8/instastorysaver/instastorysaver.jar ./instastorysaver.jar

echo "🚀 2/2 Building native ARM64 bundle with Docker..."
DOCKER_BUILDKIT=1 docker build \
  --platform linux/arm64 \
  --file Dockerfile.arm64 \
  --output type=local,dest=. \
  .

echo "✅ Successfully built: instastorysaver-linux-arm64/"
echo "👉 Run directly on any Linux ARM64 system:"
echo "   ./instastorysaver-linux-arm64/instastorysaver --destination-folder /path/to/stories ..."
