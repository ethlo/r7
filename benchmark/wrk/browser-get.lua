-- Realistic browser GET.
--
-- Header set captured from a current Chrome navigation request. This matters:
-- r7's header storage is the hot path, and a 3-header synthetic request will
-- flatter any gateway. Real traffic carries 12-18 headers on a plain GET.

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
