#!/bin/bash
wrk -t12 -c200 -d10s -s headers.lua http://localhost:8888/hello
