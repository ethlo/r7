-- Browser page GET: a Chrome navigation, as it arrives behind a load balancer.
--
-- The 16 headers of a current Chrome navigation request, plus the three the load
-- balancer in front adds. This matters: r7's header storage is the hot path, and
-- a 3-header synthetic request will flatter any gateway. The backend answers
-- with an 8 KB HTML page.

wrk.method = "GET"

wrk.headers["User-Agent"] = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
wrk.headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
wrk.headers["Accept-Encoding"] = "gzip, deflate, br, zstd"
wrk.headers["Accept-Language"] = "en-GB,en-US;q=0.9,en;q=0.8,nb;q=0.7"
wrk.headers["Cache-Control"] = "no-cache"
wrk.headers["Pragma"] = "no-cache"
wrk.headers["Sec-Ch-Ua"] = '"Chromium";v="141", "Not?A_Brand";v="24", "Google Chrome";v="141"'
wrk.headers["Sec-Ch-Ua-Mobile"] = "?0"
wrk.headers["Sec-Ch-Ua-Platform"] = '"Linux"'
wrk.headers["Sec-Fetch-Dest"] = "document"
wrk.headers["Sec-Fetch-Mode"] = "navigate"
wrk.headers["Sec-Fetch-Site"] = "none"
wrk.headers["Sec-Fetch-User"] = "?1"
wrk.headers["Upgrade-Insecure-Requests"] = "1"
wrk.headers["Cookie"] = "session=f47ac10b58cc4372a5670e02b2c3d479; theme=dark; _ga=GA1.2.1234567890.1700000000"
wrk.headers["Referer"] = "https://app.ethlo.com/dashboard"
wrk.headers["X-Forwarded-For"] = "203.0.113.42"
wrk.headers["X-Forwarded-Proto"] = "https"
wrk.headers["Traceparent"] = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
