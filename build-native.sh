#!/bin/bash
set -e
echo "🐳 Building Native Image inside Docker"
docker build -t ethlo-r7 .
echo "✅ Build Complete!"
