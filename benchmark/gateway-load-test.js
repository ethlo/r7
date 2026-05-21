import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// Custom metric to track dropped requests explicitly
export const droppedRequests = new Counter('dropped_requests');

export const options = {
    scenarios: {
        constant_load: {
            executor: 'constant-vus',
            vus: 50,
            duration: '30s',
        },
    },
    thresholds: {
        // The test fails if any requests are dropped (status is not 200)
        dropped_requests: ['count==0'],
        http_req_failed: ['rate==0.00'],
    },
};

export default function () {
    // Target the r7 gateway listener port
    const url = 'http://localhost:8888/v1/';

    const params = {
        headers: {
            'Accept': 'text/plain',
        },
        timeout: '2s',
    };

    const res = http.get(url, params);

    const success = check(res, {
        'status is 200': (r) => r.status === 200,
    });

    if (!success) {
        droppedRequests.add(1);
        console.error(`Request failed! Status: ${res.status}, Error Code: ${res.error_code}, Body: ${res.body}`);
    }

    // Small pacing delay (10ms) to prevent local CPU exhaustion
    sleep(0.01);
}