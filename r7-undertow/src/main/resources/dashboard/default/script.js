const view = document.getElementById('view');
const vitals = document.getElementById('sys-vitals');
const timeEl = document.getElementById('last-update');
const panel = document.getElementById('details-panel');
const mainContainer = document.getElementById('main-container');

const FRACTION_DIGITS = 2;
const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

if (localStorage.getItem('theme') === 'light') {
    document.body.classList.add('light-mode');
}

function getUnitIndex(b) {
    if (b === 0 || b == null) return 0;
    return Math.min(Math.floor(Math.log(b) / Math.log(1024)), UNITS.length - 1);
}

function formatBytes(b, forceUnitIndex = -1) {
    const i = forceUnitIndex >= 0 ? forceUnitIndex : getUnitIndex(b);
    if (b === 0 || b == null) {
        return forceUnitIndex >= 0 ? '0.00 ' + UNITS[i] : '0 B';
    }
    return (b / Math.pow(1024, i)).toFixed(FRACTION_DIGITS) + ' ' + UNITS[i];
}

function formatUptime(iso) {
    if (!iso) return "--";
    const match = iso.match(/P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.\d+)?S)?/);
    if (!match) return iso;

    const d = match[1] ? `${match[1]}d ` : '';
    const h = (match[2] || '0').padStart(2, '0');
    const m = (match[3] || '0').padStart(2, '0');
    const s = (match[4] || '0').padStart(2, '0');

    return `${d}${h}:${m}:${s}`;
}

// Helper to convert Java ISO-8601 Duration string (e.g., PT0.000001319S) to milliseconds
function parseDurationMs(iso) {
    if (!iso || !iso.startsWith('P')) return 0;
    let ms = 0;
    const h = iso.match(/([\d.]+)H/);
    const m = iso.match(/([\d.]+)M/);
    const s = iso.match(/([\d.]+)S/);

    if (h) ms += parseFloat(h[1]) * 3600000;
    if (m) ms += parseFloat(m[1]) * 60000;
    if (s) ms += parseFloat(s[1]) * 1000;

    return ms;
}

// Helper to convert ISO-8601 Timestamp to JS epoch, handling Java's default Epoch Zero
function parseTimestamp(iso) {
    if (!iso || iso === "1970-01-01T00:00:00Z") return 0;
    return new Date(iso).getTime();
}

function timeAgo(ts) {
    const time = typeof ts === 'string' ? parseTimestamp(ts) : ts;
    if (!time || time === 0) return 'NEVER';
    const diff = Math.floor((Date.now() - time) / 1000);
    if (diff < 2) return 'NOW';
    if (diff < 60) return `${diff}s AGO`;

    const m = Math.floor(diff / 60);
    if (m < 60) return `${m}m AGO`;

    const h = Math.floor(m / 60);
    if (h < 24) return `${h}h AGO`;

    return `${Math.floor(h / 24)}d AGO`;
}

function sumStatuses(statusObj, min, max) {
    if (!statusObj) return 0;
    let sum = 0;
    for (const [code, count] of Object.entries(statusObj)) {
        const c = parseInt(code, 10);
        if (c >= min && c <= max) sum += count;
    }
    return sum;
}

const mRow = (label, value, isSum = false, valueClass = '') => `
    <div class="${isSum ? 'sum' : 'sub'} metric-row">
        <span class="metric-label">${label}</span>
        <span class="metric-leader"></span>
        <span class="metric-value ${valueClass}">${value}</span>
    </div>`;

const jBadge = (lvl) => {
    const l = (lvl || 'NONE').toUpperCase();
    return `<span class="journal-active-${l.toLowerCase()} j-badge">${l.charAt(0)}</span>`;
};

function buildFilterPipelineHtml(node) {
    if (!node) return '<div class="sub text-muted" style="padding: 4px;">No pipeline configured.</div>';

    const phases = {
        clientReq: [],
        upstreamReq: [],
        core: 'UpstreamProxy',
        clientRes: [],
        completed: []
    };

    let curr = node;
    while (curr) {
        if (!curr.on_client_request && !curr.on_upstream_request && !curr.on_client_response && !curr.on_completed && !curr.child) {
            phases.core = curr.id;
        } else {
            if (curr.on_client_request) phases.clientReq.push(curr.id);
            if (curr.on_upstream_request) phases.upstreamReq.push(curr.id);

            if (curr.on_client_response) phases.clientRes.unshift(curr.id);
            if (curr.on_completed) phases.completed.unshift(curr.id);
        }
        curr = curr.child;
    }

    const renderPhase = (title, items, icon) => {
        if (items.length === 0) return '';
        let html = `<div style="margin-bottom: 12px;">`;
        html += `<div class="sub text-dim" style="border-bottom: 1px solid rgba(128,128,128,0.2); margin-bottom: 4px; padding-bottom: 2px;">${icon} ${title}</div>`;
        items.forEach((item, index) => {
            const connector = (index === items.length - 1) ? '└─' : '├─';
            html += `<div class="sub" style="padding-left: 4px; opacity: 0.9;"><span class="text-dim" style="margin-right: 4px;">${connector}</span>${item}</div>`;
        });
        html += `</div>`;
        return html;
    };

    let html = '<div style="font-family: monospace; font-size: 0.9em; padding: 4px;">';

    html += renderPhase('CLIENT REQUEST', phases.clientReq, '↓');
    html += renderPhase('UPSTREAM REQUEST', phases.upstreamReq, '↓');

    html += `<div style="margin-bottom: 12px;">`;
    html += `<div class="sub text-dim" style="border-bottom: 1px solid rgba(128,128,128,0.2); margin-bottom: 4px; padding-bottom: 2px;">◆ TARGET PROXY</div>`;
    html += `<div class="sub" style="padding-left: 4px; opacity: 0.9;"><span class="text-dim" style="margin-right: 4px;">└─</span>${phases.core}</div>`;
    html += `</div>`;

    html += renderPhase('CLIENT RESPONSE', phases.clientRes, '↑');
    html += renderPhase('COMPLETED', phases.completed, '↑');

    html += '</div>';

    return html;
}

function showSystemDetails() {
    const data = window.currentData;
    if (!data) return;

    window.currentOpenRoute = '__SYSTEM__';

    const stats = data.connector_statistics || {
        request_count: 0, bytes_sent: 0, bytes_received: 0, error_count: 0,
        processing_time_nanos: 0, max_processing_time_nanos: 0,
        active_connections: 0, max_active_connections: 0,
        active_requests: 0, max_active_requests: 0
    };

    const avgProcessingNanos = stats.request_count > 0 ? (stats.processing_time_nanos / stats.request_count) : 0;
    const uTotal = getUnitIndex(Math.max(stats.bytes_received, stats.bytes_sent));

    document.getElementById('details-content').innerHTML = `
        <div class="panel-header-container">
            <div style="flex-grow: 1; min-width: 0;">
                <h2 class="panel-title">System Totals</h2>
                <div class="panel-subtitle">Connector Statistics</div>
            </div>
            <button class="panel-close-btn" onclick="closeDetails()">
                CLOSE <span class="panel-close-icon">&times;</span>
            </button>
        </div>

        <div class="panel-section">
            <div class="panel-section-title">Raw Socket Traffic</div>
            ${mRow('REQUESTS PROCESSED', stats.request_count.toLocaleString(), true)}
            ${mRow('PARSER ERRORS (Dropped)', stats.error_count.toLocaleString(), false, stats.error_count > 0 ? 'text-warn' : '')}
            ${mRow('BYTES RECEIVED', formatBytes(stats.bytes_received, uTotal), false)}
            ${mRow('BYTES SENT', formatBytes(stats.bytes_sent, uTotal), false)}
        </div>

        <div class="panel-section">
            <div class="panel-section-title">Concurrency</div>
            ${mRow('ACTIVE CONNECTIONS', stats.active_connections.toLocaleString(), true, stats.active_connections > 0 ? 'text-http' : '')}
            ${mRow('PEAK CONNECTIONS', stats.max_active_connections.toLocaleString(), false)}
            ${mRow('ACTIVE REQUESTS', stats.active_requests.toLocaleString(), false)}
            ${mRow('PEAK REQUESTS', stats.max_active_requests.toLocaleString(), false)}
        </div>

        <div class="panel-section">
            <div class="panel-section-title">Global Latency (Socket Level)</div>
            ${mRow('AVERAGE PROCESSING', (avgProcessingNanos / 1000000).toFixed(FRACTION_DIGITS) + 'ms', true)}
            ${mRow('MAXIMUM PROCESSING', (stats.max_processing_time_nanos / 1000000).toFixed(FRACTION_DIGITS) + 'ms', false)}
        </div>
        
        <div class="panel-section">
            <div class="panel-section-title">Diagnostic Info</div>
            <div class="sub text-dim" style="padding: 8px;">
                These metrics represent the raw physical truth at the TCP/NIO layer, before any routing, filtering, or application logic occurs. 
                Discrepancies between these numbers and the route aggregations indicate early socket terminations, malformed HTTP payloads, or unroutable dark traffic.
            </div>
        </div>
    `;

    panel.classList.add('open');
    mainContainer.style.marginRight = '550px';
}

function showDetails(routeId) {
    const config = window.currentConfigs[routeId];
    const metrics = window.currentMetrics[routeId];
    if (!config || !metrics) return;

    window.currentOpenRoute = routeId;

    const t = metrics.traffic_flow;
    const s = metrics.request_statistics;
    const p = metrics.performance_telemetry;

    const st2xx = sumStatuses(s.client_statuses, 200, 299);
    const st3xx = sumStatuses(s.client_statuses, 300, 399);
    const err4xx = sumStatuses(s.client_statuses, 400, 499);
    const err5xx = sumStatuses(s.client_statuses, 500, 599);
    const wsTotal = s.websocket_total || 0;
    const wsActive = s.websocket_active || 0;

    const r4xxStyle = err4xx > 0 ? 'text-warn' : '';
    const r5xxStyle = err5xx > 0 ? 'text-err' : '';
    const rActiveHttpStyle = s.active > 0 ? 'text-http' : '';
    const rActiveWsStyle = wsActive > 0 ? 'text-ws' : '';

    const uRoute = getUnitIndex(Math.max(t.ingress.total_bytes, t.egress.total_bytes, t.journal_storage_bytes));
    const reqJourn = config.journal?.request || 'NONE';
    const resJourn = config.journal?.response || 'NONE';

    const buildPills = (activeLevel) => {
        const levels = ['NONE', 'METADATA', 'HEADERS', 'FULL'];
        return `<div class="pill-group">` +
            levels.map(lvl => {
                const activeClass = lvl === activeLevel ? ` journal-active-${lvl.toLowerCase()}` : '';
                return `<span class="journal-tag${activeClass}">${lvl}</span>`;
            }).join('') + `</div>`;
    };

    let pHtml = '<div class="tree-view">';
    if (config.match && config.match.expression) {
        pHtml += `
            <div class="sub tree-node">
                <span class="text-nowrap">└─ MATCH</span>
                <span class="text-dim text-truncate" title="${config.match.expression}">${config.match.expression}</span>
            </div>`;
    } else {
        pHtml += `<div class="sub text-muted">Default (All Traffic)</div>`;
    }
    pHtml += '</div>';

    let fHtml = '';
    if (config.filter_nodes) {
        fHtml = buildFilterPipelineHtml(config.filter_nodes);
    } else if (config.filters && config.filters.length > 0) {
        fHtml += '<div class="tree-view" style="font-family: monospace; font-size: 0.9em; padding: 4px;">';
        config.filters.forEach((f, i) => {
            const branch = (i === config.filters.length - 1) ? '└─' : '├─';
            fHtml += `
                <div class="sub tree-node">
                    <span class="text-nowrap">${branch} ${f.name}</span>
                    <span class="text-dim text-truncate" title='${JSON.stringify(f.args)}'>${JSON.stringify(f.args)}</span>
                </div>`;
        });
        fHtml += '</div>';
    } else {
        fHtml = `<div class="sub text-muted" style="padding: 4px 4px;">No filters configured.</div>`;
    }

    const generateStatusRows = (statusMap) => {
        if (!statusMap || Object.keys(statusMap).length === 0) {
            return `<div class="sub text-muted" style="padding: 4px 4px;">No statuses recorded.</div>`;
        }
        return Object.entries(statusMap).map(([code, count]) => {
            const codeNum = parseInt(code, 10);
            const colorClass = codeNum >= 500 ? 'text-err' : (codeNum >= 400 ? 'text-warn' : '');
            return mRow(`HTTP ${code}`, count.toLocaleString(), false, colorClass);
        }).join('');
    };

    document.getElementById('details-content').innerHTML = `
        <div class="panel-header-container">
            <div style="flex-grow: 1; min-width: 0;">
                <h2 class="panel-title">${routeId}</h2>
            </div>
            <button class="panel-close-btn" onclick="closeDetails()">
                CLOSE <span class="panel-close-icon">&times;</span>
            </button>
        </div>
        
        <div class="panel-section">
            <div class="panel-section-title">Routing target</div>
                <div class="sub tree-node">
                <span class="text-nowrap">└─ TARGET</span>
                <span class="text-dim text-truncate" title="${config.destination || 'N/A'}">${config.destination || 'N/A'}
                </span>
            </div>
        </div>

        <div class="panel-section">
            <div class="panel-section-title">Routing Predicate</div>
            ${pHtml}
        </div>
        
        <div class="panel-section">
            <div class="panel-section-title">Execution Pipeline</div>
            ${fHtml}
        </div>
        
        <div class="panel-section">
            <div class="panel-section-title">Status</div>
            ${mRow('LAST ACTIVE', timeAgo(s.last_active), true)}
            ${mRow('HTTP ACTIVE', s.active, false, rActiveHttpStyle)}
            ${mRow('WEBSOCKET ACTIVE', wsActive, false, rActiveWsStyle)}
            ${mRow('AVERAGE LATENCY', parseDurationMs(p.average_latency).toFixed(FRACTION_DIGITS) + 'ms', false)}
        </div>

        <div class="panel-section">
            <div class="panel-section-title">Requests (Aggregated)</div>
            ${mRow('HTTP TOTAL', s.total.toLocaleString(), true)}
            ${mRow('WEBSOCKET TOTAL', wsTotal.toLocaleString(), false)}
            ${mRow('2xx SUCCESS', st2xx.toLocaleString(), false)}
            ${mRow('3xx REDIRECT', st3xx.toLocaleString(), false)}
            ${mRow('4xx ERROR', err4xx.toLocaleString(), false, r4xxStyle)}
            ${mRow('5xx ERROR', err5xx.toLocaleString(), false, r5xxStyle)}
        </div>

        <div class="panel-section">
            <div class="panel-section-title">Client Responses</div>
            ${generateStatusRows(s.client_statuses)}
        </div>

        <div class="panel-section">
            <div class="panel-section-title">Upstream Responses</div>
            ${generateStatusRows(s.upstream_statuses)}
        </div>

        <div class="panel-section">
            <div class="panel-section-title">Traffic</div>
            ${mRow('INGRESS TOTAL', formatBytes(t.ingress.total_bytes, uRoute), true)}
            ${mRow('INGRESS HEADERS', formatBytes(t.ingress.header_bytes, uRoute), false)}
            ${mRow('INGRESS BODY', formatBytes(t.ingress.body_bytes, uRoute), false)}
            <div class="spacer-sm"></div>
            ${mRow('EGRESS TOTAL', formatBytes(t.egress.total_bytes, uRoute), true)}
            ${mRow('EGRESS HEADERS', formatBytes(t.egress.header_bytes, uRoute), false)}
            ${mRow('EGRESS BODY', formatBytes(t.egress.body_bytes, uRoute), false)}
        </div>

        <div class="panel-section">
            <div class="panel-section-title">Journaling</div>
            ${mRow('STORAGE USED', formatBytes(t.journal_storage_bytes, uRoute), true)}
            ${mRow('REQUEST', buildPills(reqJourn), false)}
            ${mRow('RESPONSE', buildPills(resJourn), false)}
        </div>
    `;

    panel.classList.add('open');
    mainContainer.style.marginRight = '550px';
}

document.addEventListener('click', (e) => {
    if (panel.classList.contains('open') &&
        !panel.contains(e.target) &&
        !e.target.closest('tr.clickable-row')) {
        closeDetails();
    }
});

function closeDetails() {
    panel.classList.remove('open');
    mainContainer.style.marginRight = 'auto';
}

async function update() {
    try {
        const res = await fetch(window.location.href, {headers: {'Accept': 'application/json'}});
        const data = await res.json();

        window.currentData = data;

        const disk = data.journaling?.available_space ? formatBytes(data.journaling.available_space) : '--';
        const unroutable = data.connector_statistics?.error_count || 0;
        const activeConns = data.connector_statistics?.active_connections || 0;

        vitals.innerText = `UPTIME: ${formatUptime(data.system.uptime)} | DISK: ${disk} FREE | CONNECTIONS: ${activeConns}`;

        const version = data.system.version || 'Unknown';
        document.getElementById('sys-version').innerText = version;

        timeEl.innerHTML = `<span class="status-indicator" style="color: #00aa00;">●</span>ONLINE`;

        window.currentConfigs = {};
        if (data.route_configs) {
            data.route_configs.forEach(c => window.currentConfigs[c.id] = c);
        }

        window.currentMetrics = {};
        if (data.route_metrics) {
            data.route_metrics.forEach(m => window.currentMetrics[m.id] = m);
        }

        renderTable(data);

        if (panel.classList.contains('open')) {
            if (window.currentOpenRoute === '__SYSTEM__') {
                showSystemDetails();
            } else if (window.currentOpenRoute) {
                showDetails(window.currentOpenRoute);
            }
        }
    } catch (e) {
        console.error(e)
        timeEl.innerHTML = `<span class="status-indicator" style="color: #ff4444;">●</span>OFFLINE`;
    }
}

const ZERO_METRICS = {
    request_statistics: {
        total: 0,
        websocket_total: 0,
        active: 0,
        websocket_active: 0,
        last_active: "1970-01-01T00:00:00Z",
        client_statuses: {}
    },
    traffic_flow: {
        ingress: {total_bytes: 0, header_bytes: 0, body_bytes: 0},
        egress: {total_bytes: 0, header_bytes: 0, body_bytes: 0},
        journal_storage_bytes: 0
    },
    performance_telemetry: {average_latency: "PT0S"}
};

function drawStackedSparkline(canvas, data) {
    const ctx = canvas.getContext('2d');
    const width = canvas.width;
    const height = canvas.height;

    ctx.clearRect(0, 0, width, height);

    if (!data) return;

    const successData = data.success || [];
    const clientData = data.client_error || [];
    const serverData = data.server_error || [];

    const len = Math.max(successData.length, clientData.length, serverData.length);
    if (len === 0) return;

    let maxTotal = 0;
    for (let i = 0; i < len; i++) {
        // STRICT CAST: Prevent "5" + "227442" = "5227442" string concatenation bugs
        const total = (Number(successData[i]) || 0) + (Number(clientData[i]) || 0) + (Number(serverData[i]) || 0);
        if (total > maxTotal) {
            maxTotal = total;
        }
    }

    if (maxTotal === 0) {
        ctx.fillStyle = '#333333';
        ctx.fillRect(0, height - 1, width, 1);
        return;
    }

    const barWidth = width / len;

    for (let i = 0; i < len; i++) {
        const sVal = Number(successData[i]) || 0;
        const cVal = Number(clientData[i]) || 0;
        const eVal = Number(serverData[i]) || 0;
        const total = sVal + cVal + eVal;

        if (total === 0) continue;

        // Calculate heights and enforce the 1-pixel minimum visibility rule
        let sHeight = Math.max(sVal > 0 ? 1 : 0, (sVal / maxTotal) * height);
        let cHeight = Math.max(cVal > 0 ? 1 : 0, (cVal / maxTotal) * height);
        let eHeight = Math.max(eVal > 0 ? 1 : 0, (eVal / maxTotal) * height);

        // Safety: If the 1-pixel minimums push us over the canvas height, scale them back proportionally
        const calcTotal = sHeight + cHeight + eHeight;
        if (calcTotal > height) {
            const shrink = height / calcTotal;
            sHeight *= shrink;
            cHeight *= shrink;
            eHeight *= shrink;
        }

        // Math.round ensures we don't infinitely stack on the exact same sub-pixel X coordinate
        const x = Math.round(i * barWidth);
        const drawWidth = Math.max(1, Math.ceil(barWidth));

        let currentY = height;

        if (sHeight > 0) {
            currentY -= sHeight;
            ctx.fillStyle = '#00cc55';
            ctx.fillRect(x, currentY, drawWidth, sHeight);
        }

        if (cHeight > 0) {
            currentY -= cHeight;
            ctx.fillStyle = '#ff9800';
            ctx.fillRect(x, currentY, drawWidth, cHeight);
        }

        if (eHeight > 0) {
            currentY -= eHeight;
            ctx.fillStyle = '#ee4444';
            ctx.fillRect(x, currentY, drawWidth, eHeight);
        }
    }
}

function renderTable(data) {
    if (!data.route_configs) return;

    const totals = (data.route_metrics || []).reduce((acc, r) => ({
        total: acc.total + (r.request_statistics.total || 0),
        ws_total: acc.ws_total + (r.request_statistics.websocket_total || 0),
        st_2xx: acc.st_2xx + sumStatuses(r.request_statistics.client_statuses, 200, 299),
        st_3xx: acc.st_3xx + sumStatuses(r.request_statistics.client_statuses, 300, 399),
        err_4xx: acc.err_4xx + sumStatuses(r.request_statistics.client_statuses, 400, 499),
        err_5xx: acc.err_5xx + sumStatuses(r.request_statistics.client_statuses, 500, 599),
        active: acc.active + (r.request_statistics.active || 0),
        ws_active: acc.ws_active + (r.request_statistics.websocket_active || 0),
        last_active: Math.max(acc.last_active, parseTimestamp(r.request_statistics.last_active)),
        in_h: acc.in_h + r.traffic_flow.ingress.header_bytes,
        in_b: acc.in_b + r.traffic_flow.ingress.body_bytes,
        in_t: acc.in_t + r.traffic_flow.ingress.total_bytes,
        out_h: acc.out_h + r.traffic_flow.egress.header_bytes,
        out_b: acc.out_b + r.traffic_flow.egress.body_bytes,
        out_t: acc.out_t + r.traffic_flow.egress.total_bytes,
        journal: acc.journal + r.traffic_flow.journal_storage_bytes,
        latency: acc.latency + (parseDurationMs(r.performance_telemetry.average_latency) * (r.request_statistics.total || 0))
    }), {
        total: 0, ws_total: 0, st_2xx: 0, st_3xx: 0, err_4xx: 0, err_5xx: 0, active: 0, ws_active: 0, last_active: 0,
        in_h: 0, in_b: 0, in_t: 0, out_h: 0, out_b: 0, out_t: 0, journal: 0, latency: 0
    });

    const globalAvgLat = totals.total === 0 ? '0.00' : (totals.latency / totals.total).toFixed(FRACTION_DIGITS);
    const uTotal = getUnitIndex(Math.max(totals.in_t, totals.out_t, totals.journal));

    const t2xxStyle = 'text-2xx';
    const t3xxStyle = 'text-3xx';
    const t4xxStyle = totals.err_4xx > 0 ? 'text-4xx' : '';
    const t5xxStyle = totals.err_5xx > 0 ? 'text-5xx' : '';
    const tActiveHttpStyle = totals.active > 0 ? 'text-http' : '';
    const tActiveWsStyle = totals.ws_active > 0 ? 'text-ws' : '';

    const last = function (arr) {
        return arr[arr.length - 1];
    }

    let html = '';

    html += `
        <tr class="total-row clickable-row" onclick="showSystemDetails()">
            <td>&nbsp;</td>
            <td class="col-route">
                <div class="sum" style="margin: 0; line-height: 1.1; padding-bottom: 2px;">SYSTEM TOTALS</div>
                <div class="sub" style="margin: 0; line-height: 1.1;">LAST: ${timeAgo(totals.last_active)}</div>
            </td>
            <td class="col-success">
                ${mRow('SUM', totals.total.toLocaleString(), true)}
                ${mRow('2xx', totals.st_2xx.toLocaleString(), false, t2xxStyle)}
                ${mRow('3xx', totals.st_3xx.toLocaleString(), false, t3xxStyle)}
                ${mRow('4xx', totals.err_4xx.toLocaleString(), false, t4xxStyle)}
                ${mRow('5xx', totals.err_5xx.toLocaleString(), false, t5xxStyle)}
            </td>
            <td class="col-ingress">
                ${mRow('SUM', formatBytes(totals.in_t, uTotal), true)}
                ${mRow('HDR', formatBytes(totals.in_h, uTotal), false)}
                ${mRow('BDY', formatBytes(totals.in_b, uTotal), false)}
            </td>
            <td class="col-egress">
                ${mRow('SUM', formatBytes(totals.out_t, uTotal), true)}
                ${mRow('HDR', formatBytes(totals.out_h, uTotal), false)}
                ${mRow('BDY', formatBytes(totals.out_b, uTotal), false)}
            </td>
            <td class="col-active">
                ${mRow('ACT', totals.active, true, tActiveHttpStyle)}
                ${mRow('LAT', globalAvgLat + 'ms', true)}
            </td>
            <td class="col-journal">
                ${mRow('SIZE', formatBytes(totals.journal, uTotal), true)}
                ${mRow('REQ', '-', false)}
                ${mRow('RES', '-', false)}
            </td>
        </tr>`;

    data.route_configs.forEach(config => {
        const r = (data.route_metrics || []).find(m => m.id === config.id) || ZERO_METRICS;

        const t = r.traffic_flow;
        const s = r.request_statistics;
        const p = r.performance_telemetry;

        const st2xx = sumStatuses(s.client_statuses, 200, 299);
        const st3xx = sumStatuses(s.client_statuses, 300, 399);
        const err4xx = sumStatuses(s.client_statuses, 400, 499);
        const err5xx = sumStatuses(s.client_statuses, 500, 599);
        const wsTotal = s.websocket_total || 0;
        const wsActive = s.websocket_active || 0;

        const reqJourn = config.journal?.request || 'NONE';
        const resJourn = config.journal?.response || 'NONE';

        const uRoute = getUnitIndex(Math.max(t.ingress.total_bytes, t.egress.total_bytes, t.journal_storage_bytes));

        const r4xxStyle = err4xx > 0 ? 'text-warn' : '';
        const r5xxStyle = err5xx > 0 ? 'text-err' : '';
        const rActiveHttpStyle = s.active > 0 ? 'text-http' : '';
        const rActiveWsStyle = wsActive > 0 ? 'text-ws' : '';

        html += `
        <tr class="clickable-row" onclick="showDetails('${config.id}')">
            <td>
                <canvas id="canvas_${config.id}" width="150" height="60"></canvas>
            </td>
            <td class="col-route">
                <div class="sum" style="margin: 0; line-height: 1.1; padding-bottom: 2px;">${config.id}</div>
                <div class="sub" style="margin: 0; line-height: 1.1;">LAST: ${timeAgo(s.last_active)}</div>
            </td>
            <td class="col-totals">
                ${mRow('SUM', s.total.toLocaleString(), true)}
                ${mRow('2xx', st2xx.toLocaleString(), false, t2xxStyle)}
                ${mRow('3xx', st3xx.toLocaleString(), false, t3xxStyle)}
                ${mRow('4xx', err4xx.toLocaleString(), false, r4xxStyle)}
                ${mRow('5xx', err5xx.toLocaleString(), false, r5xxStyle)}
            </td>
            <td class="col-ingress">
                ${mRow('SUM', formatBytes(t.ingress.total_bytes, uRoute), true)}
                ${mRow('HDR', formatBytes(t.ingress.header_bytes, uRoute), false)}
                ${mRow('BDY', formatBytes(t.ingress.body_bytes, uRoute), false)}
            </td>
            <td class="col-egress">
                ${mRow('SUM', formatBytes(t.egress.total_bytes, uRoute), true)}
                ${mRow('HDR', formatBytes(t.egress.header_bytes, uRoute), false)}
                ${mRow('BDY', formatBytes(t.egress.body_bytes, uRoute), false)}
            </td>
            <td class="col-active">
                ${mRow('HTTP', s.active, true, rActiveHttpStyle)}
                ${mRow('AVG', parseDurationMs(p.average_latency).toFixed(FRACTION_DIGITS) + 'ms', true)}
                ${mRow('RPS', ((last(r.sparkline_data.success) + last(r.sparkline_data.client_error) + last(r.sparkline_data.server_error)) / 2).toLocaleString())}
            </td>
            <td class="col-journal">
                ${mRow('SIZE', formatBytes(t.journal_storage_bytes, uRoute), true)}
                ${mRow('REQ', jBadge(reqJourn), false)}
                ${mRow('RES', jBadge(resJourn), false)}
            </td>
        </tr>`;
    });

    view.innerHTML = html;

    data.route_configs.forEach((config, index) => {
        const canvas = document.getElementById(`canvas_${config.id}`);
        const metrics = (data.route_metrics || []).find(m => m.id === config.id);
        drawStackedSparkline(canvas, metrics.sparkline_data);
    });
}

setInterval(update, 1000);
update();