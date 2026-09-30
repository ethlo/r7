#!/bin/bash
set -e
echo "🐳 Building Native Image inside Docker"
docker build -t r7-gateway-native -f Dockerfile.native .
echo "✅ Build Complete!"
