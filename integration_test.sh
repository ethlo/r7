#!/bin/bash

TARGET_HOST="localhost"
TARGET_PORT="8888"
TARGET_URL="http://$TARGET_HOST:$TARGET_PORT/hello"

echo "--- R7 Stress Suite V2 ---"

# 1. Bypassing ARG_MAX for the 1MB Header
# We write the header to a file and tell curl to read it, avoiding the shell limit.
echo -n "[*] Testing 1MB Giant Header (via File)... "
printf "A%.0s" {1..1048576} > giant_header.txt
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Giant: $(cat giant_header.txt)" "$TARGET_URL" 2>/dev/null || \
(printf "GET /hello HTTP/1.1\r\nHost: $TARGET_HOST\r\nX-Giant: " && cat giant_header.txt && printf "\r\n\r\n") | nc $TARGET_HOST $TARGET_PORT | grep "HTTP/" | awk '{print $2}'
rm giant_header.txt

# 2. Advanced CRLF Smuggling Probe
# Since you got a 200 OK on CRLF, let's see if we can "hide" a second request.
# This probes for TE.CL smuggling.
echo -n "[*] Probing for Request Smuggling (TE.CL)... "
printf "POST /hello HTTP/1.1\r\nHost: $TARGET_HOST\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n5c\r\nGPOST /hello HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 15\r\n\r\nx=1\r\n0\r\n\r\n" | nc $TARGET_HOST $TARGET_PORT | grep "HTTP/" | awk '{print $2}'

# 3. The "Header Count" Stress
# Undertow default is 200 headers. Let's send 201.
echo -n "[*] Testing Max Header Count (201 headers)... "
HEADERS=""
for i in {1..201}; do HEADERS="$HEADERS -H 'X-Header-$i: value'"; done
eval "curl -s -o /dev/null -w '%{http_code}\n' $HEADERS $TARGET_URL"

echo "--- Suite Complete ---"