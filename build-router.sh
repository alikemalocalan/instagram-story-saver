#!/bin/bash
set -e

echo "🚀 Building self-contained native ARM64 bundle for GL.iNet GL-MT3000..."

DOCKER_BUILDKIT=1 docker build \
  --platform linux/arm64 \
  --file Dockerfile.router \
  --output type=local,dest=. \
  .

echo "✅ Successfully built: instastorysaver-router/"
echo "👉 Copy the folder to your GL-MT3000 router via SCP:"
echo "   scp -r instastorysaver-router root@192.168.8.1:/mnt/sda1/"
echo "👉 On the router, run directly without installing ANY package or JRE:"
echo "   /mnt/sda1/instastorysaver-router/instastorysaver --destination-folder /mnt/sda1/instagram-stories ..."
