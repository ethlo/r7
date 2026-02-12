#!/bin/bash
set -e
echo "🐳 Building Native Image inside Docker"
docker build -t ethlo-venturi .
echo "✅ Build Complete!"
