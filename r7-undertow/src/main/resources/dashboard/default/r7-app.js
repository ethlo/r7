const view = document.getElementById('view');
const vitals = document.getElementById('sys-vitals');
const timeEl = document.getElementById('last-update');
const panel = document.getElementById('details-panel');
const mainContainer = document.getElementById('main-container');

if (localStorage.getItem('theme') === 'light') {
    document.body.classList.add('light-mode');
}

const PER_SEC = '/s';

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

    const last = function (arr) {
        return (arr && arr.length > 0) ? arr[arr.length - 1] : 0;
    };

    let sys2xxRps = 0, sys4xxRps = 0, sys5xxRps = 0;
    data.route_configs.forEach(c => {
        const r = (data.route_metrics || []).find(m => m.id === c.id);
        if (r && r.sparkline_data) {
            sys2xxRps += last(r.sparkline_data.success) / 2;
            sys4xxRps += last(r.sparkline_data.client_error) / 2;
            sys5xxRps += last(r.sparkline_data.server_error) / 2;
        }
    });
    const sysRpsTotal = sys2xxRps + sys4xxRps + sys5xxRps;

    let html = '';

    html += `
        <tr class="total-row clickable-row" onclick="showSystemDetails()">
            <td class="col-route">
                <div class="sum" style="margin: 0; line-height: 1.1; padding-bottom: 2px;">SYSTEM TOTALS - ${timeAgo(totals.last_active)}</div>
                <canvas id="canvas___SYSTEM__" width="200" height="70" style="margin-top: 8px;"></canvas>
            </td>
            <td class="col-totals">
                ${mRow('SUM', formatCompact(totals.total), true, '', formatCompact(Math.round(sysRpsTotal)) + PER_SEC)}
                ${mRow('2xx', formatCompact(totals.st_2xx), false, t2xxStyle, formatCompact(Math.round(sys2xxRps)) + PER_SEC)}
                ${mRow('4xx', formatCompact(totals.err_4xx), false, t4xxStyle, formatCompact(Math.round(sys4xxRps)) + PER_SEC)}
                ${mRow('5xx', formatCompact(totals.err_5xx), false, t5xxStyle, formatCompact(Math.round(sys5xxRps)) + PER_SEC)}
            </td>
            <td class="col-ingress">
                ${mRow('SUM', formatBytes(totals.in_t, uTotal), true, '', formatBytes(totals.out_t, uTotal))}
                ${mRow('HDR', formatBytes(totals.in_h, uTotal), false, '', formatBytes(totals.out_h, uTotal))}
                ${mRow('BDY', formatBytes(totals.in_b, uTotal), false, '', formatBytes(totals.out_b, uTotal))}
            </td>
            <td class="col-active">
                ${mRow('ACT', totals.active, true, tActiveHttpStyle)}
                ${mRow('LAT', globalAvgLat + 'ms', true)}
            </td>
            <td class="col-journal">
                ${mRow('SIZE', formatBytes(totals.journal, uTotal), true)}
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

        const reqJourn = config.journal?.request || 'NONE';
        const resJourn = config.journal?.response || 'NONE';

        const uRoute = getUnitIndex(Math.max(t.ingress.total_bytes, t.egress.total_bytes, t.journal_storage_bytes));
        const rActiveHttpStyle = s.active > 0 ? 'text-http' : '';

        const r2xxRps = r.sparkline_data ? last(r.sparkline_data.success) / 2 : 0;
        const r4xxRps = r.sparkline_data ? last(r.sparkline_data.client_error) / 2 : 0;
        const r5xxRps = r.sparkline_data ? last(r.sparkline_data.server_error) / 2 : 0;
        const rRpsTotal = r2xxRps + r4xxRps + r5xxRps;

        html += `
        <tr class="clickable-row" onclick="showDetails('${config.id}')">
            <td class="col-route">
                <div class="sum" style="margin: 0; line-height: 1.1; padding-bottom: 2px;">${config.id} - ${timeAgo(s.last_active)}</div>
                <canvas id="canvas_${config.id}" width="200" height="70" style="margin-top: 8px;"></canvas>
            </td>
            <td class="col-totals">
                ${mRow('SUM', formatCompact(s.total), true, '', formatCompact(Math.round(rRpsTotal)) + PER_SEC)}
                ${mRow('2xx', formatCompact(st2xx), false, t2xxStyle, formatCompact(Math.round(r2xxRps)) + PER_SEC)}
                ${mRow('4xx', formatCompact(err4xx), false, t4xxStyle, formatCompact(Math.round(r4xxRps)) + PER_SEC)}
                ${mRow('5xx', formatCompact(err5xx), false, t5xxStyle, formatCompact(Math.round(r5xxRps)) + PER_SEC)}
            </td>
            <td class="col-ingress">
                ${mRow('SUM', formatBytes(t.ingress.total_bytes, uRoute), true, '', formatBytes(t.egress.total_bytes, uRoute))}
                ${mRow('HDR', formatBytes(t.ingress.header_bytes, uRoute), false, '', formatBytes(t.egress.header_bytes, uRoute))}
                ${mRow('BDY', formatBytes(t.ingress.body_bytes, uRoute), false, '', formatBytes(t.egress.body_bytes, uRoute))}
            </td>
            <td class="col-active">
                ${mRow('ACT', s.active, true, rActiveHttpStyle)}
                ${mRow('AVG', parseDurationMs(p.average_latency).toFixed(FRACTION_DIGITS) + 'ms', true)}
            </td>
            <td class="col-journal">
                ${mRow('SIZE', formatBytes(t.journal_storage_bytes, uRoute), true)}
                ${mRow('LVL', 'REQ: ' + jBadge(reqJourn), false, '', 'RES: ' + jBadge(resJourn))}
            </td>
        </tr>`;
    });

    view.innerHTML = html;

    const sysSparkline = { success: [], client_error: [], server_error: [] };
    const bufferSize = data.route_metrics && data.route_metrics[0] && data.route_metrics[0].sparkline_data ? data.route_metrics[0].sparkline_data.success.length : 0;

    if (bufferSize > 0) {
        for(let i=0; i<bufferSize; i++) {
            sysSparkline.success[i] = 0;
            sysSparkline.client_error[i] = 0;
            sysSparkline.server_error[i] = 0;
        }

        (data.route_metrics || []).forEach(m => {
            if (m.sparkline_data) {
                for(let i=0; i<bufferSize; i++) {
                    sysSparkline.success[i] += (m.sparkline_data.success[i] || 0);
                    sysSparkline.client_error[i] += (m.sparkline_data.client_error[i] || 0);
                    sysSparkline.server_error[i] += (m.sparkline_data.server_error[i] || 0);
                }
            }
        });

        const sysCanvas = document.getElementById('canvas___SYSTEM__');
        if (sysCanvas) drawStackedSparkline(sysCanvas, sysSparkline);
    }

    data.route_configs.forEach(config => {
        const canvas = document.getElementById(`canvas_${config.id}`);
        const metrics = (data.route_metrics || []).find(m => m.id === config.id);
        if (canvas && metrics && metrics.sparkline_data) {
            drawStackedSparkline(canvas, metrics.sparkline_data);
        }
    });
}

async function update() {
    try {
        const res = await fetch(window.location.href, {headers: {'Accept': 'application/json'}});
        const data = await res.json();

        window.currentData = data;

        const disk = data.journaling?.available_space ? formatBytes(data.journaling.available_space) : '--';
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

setInterval(update, 1000);
update();