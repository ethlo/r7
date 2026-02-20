#!/bin/bash

URL="http://localhost:8888/hello"
THREADS=12
CONNECTIONS=200
DURATION="10s"

# Use an array to handle the spaces and quotes properly
HEADERS=()
for i in {1..10}; do
    HEADERS+=("-H" "X-Venturi-Header-$i: value-$i")
done

#echo "--- Starting Warmup (10s) ---"
# Warmup to let JIT optimize the ArrayBackedPairStorage
#wrk -t$THREADS -c$CONNECTIONS -d10s "${HEADERS[@]}" "$URL" > /dev/null

#echo "--- Cooling down (3s) ---"
#sleep 3

echo "--- Running Performance Test ($DURATION) ---"
# "${HEADERS[@]}" expands the array correctly as individual arguments
wrk -t$THREADS -c$CONNECTIONS -d$DURATION --latency "${HEADERS[@]}" "$URL"

echo "--- Benchmark Complete ---"