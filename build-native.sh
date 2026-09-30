#!/bin/bash
set -e
# On hold: see the note at the top of Dockerfile.native.
echo "🐳 Building Native Image inside Docker"
docker build -t r7-gateway-native -f Dockerfile.native .
echo "✅ Build Complete!"
