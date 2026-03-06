const view = document.getElementById('view');
const vitals = document.getElementById('sys-vitals');
const timeEl = document.getElementById('last-update');
const panel = document.getElementById('details-panel');
const mainContainer = document.getElementById('main-container');

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

// Theme Toggle Logic
const themeBtn = document.getElementById('theme-toggle');

// Check local storage for saved preference on load
if (localStorage.getItem('theme') === 'light') {
    document.body.classList.add('light-mode');
}

/*
themeBtn.addEventListener('click', () => {
    document.body.classList.toggle('light-mode');

    // Save preference to persist across the 1-second update cycle and page reloads
    if (document.body.classList.contains('light-mode')) {
        localStorage.setItem('theme', 'light');
    } else {
        localStorage.setItem('theme', 'dark');
    }
});
*/
function getUnitIndex(b) {
    if (b === 0 || b == null) return 0;
    return Math.min(Math.floor(Math.log(b) / Math.log(1024)), UNITS.length - 1);
}

function formatBytes(b, forceUnitIndex = -1) {
    const i = forceUnitIndex >= 0 ? forceUnitIndex : getUnitIndex(b);
    if (b === 0 || b == null) {
        return forceUnitIndex >= 0 ? '0.00 ' + UNITS[i] : '0 B';
    }
    return (b / Math.pow(1024, i)).toFixed(2) + ' ' + UNITS[i];
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

/**
 * Converts an epoch timestamp into a relative infrastructure-style string.
 */
function timeAgo(ts) {
    if (!ts || ts === 0) return 'NEVER';
    const diff = Math.floor((Date.now() - ts) / 1000);
    if (diff < 2) return 'NOW';
    if (diff < 60) return `${diff}s AGO`;

    const m = Math.floor(diff / 60);
    if (m < 60) return `${m}m AGO`;

    const h = Math.floor(m / 60);
    if (h < 24) return `${h}h AGO`;

    return `${Math.floor(h / 24)}d AGO`;
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

        // Update Header Vitals
        const disk = data.journaling?.available_space ? formatBytes(data.journaling.available_space) : '--';
        vitals.innerText = `UPTIME: ${formatUptime(data.system.uptime)} | DISK: ${disk} FREE`;

        // Update Footer Version
        document.getElementById('sys-version').innerText = data.system.version || 'Unknown';

        timeEl.innerHTML = `<span class="status-indicator" style="color: #00ff88;">●</span>ONLINE`;

        const routeConfigs = {};
        if (data.route_configs) {
            data.route_configs.forEach(c => routeConfigs[c.id] = c);
        }
        window.currentConfigs = routeConfigs;

        renderTable(data);
    } catch (e) {
        timeEl.innerHTML = `<span class="status-indicator" style="color: #ff4444;">●</span>OFFLINE`;
    }
}

function renderTable(data) {
    if (!data.route_metrics) return;

    // Aggregate totals
    const totals = data.route_metrics.reduce((acc, r) => ({
        total: acc.total + (r.request_statistics.total || 0),
        ws_total: acc.ws_total + (r.request_statistics.websocket_total || 0),
        st_2xx: acc.st_2xx + (r.request_statistics.status_2xx || 0),
        st_3xx: acc.st_3xx + (r.request_statistics.status_3xx || 0),
        err_4xx: acc.err_4xx + (r.request_statistics.status_4xx || 0),
        err_5xx: acc.err_5xx + (r.request_statistics.status_5xx || 0),
        active: acc.active + (r.request_statistics.active || 0),
        ws_active: acc.ws_active + (r.request_statistics.websocket_active || 0),
        last_active: Math.max(acc.last_active, (r.request_statistics.last_active_ts || 0)),
        in_h: acc.in_h + r.traffic_flow.ingress.header_bytes,
        in_b: acc.in_b + r.traffic_flow.ingress.body_bytes,
        in_t: acc.in_t + r.traffic_flow.ingress.total_bytes,
        out_h: acc.out_h + r.traffic_flow.egress.header_bytes,
        out_b: acc.out_b + r.traffic_flow.egress.body_bytes,
        out_t: acc.out_t + r.traffic_flow.egress.total_bytes,
        journal: acc.journal + r.traffic_flow.journal_storage_bytes,
        latency: acc.latency + (r.performance_telemetry.average_latency_nanoseconds * (r.request_statistics.total || 0))
    }), {
        total: 0, ws_total: 0, st_2xx: 0, st_3xx: 0, err_4xx: 0, err_5xx: 0, active: 0, ws_active: 0, last_active: 0,
        in_h: 0, in_b: 0, in_t: 0, out_h: 0, out_b: 0, out_t: 0, journal: 0, latency: 0
    });

    const globalAvgLat = totals.total === 0 ? 0 : (totals.latency / totals.total / 1000000).toFixed(3);
    const uTotal = getUnitIndex(Math.max(totals.in_t, totals.out_t, totals.journal));

    // Conditional Styles for Totals
    const t4xxStyle = totals.err_4xx > 0 ? 'color: #ffca28; font-weight: 600;' : '';
    const t5xxStyle = totals.err_5xx > 0 ? 'color: #ff4444; font-weight: 600;' : '';
    const tActiveHttpStyle = totals.active > 0 ? 'color: #00d4ff; font-weight: 600;' : '';
    const tActiveWsStyle = totals.ws_active > 0 ? 'color: #00ff88; font-weight: 600;' : '';

    let html = `
        <tr class="total-row">
            <td>
                <div class="sum">SYSTEM TOTALS</div>
                <div class="sub">LAST: ${timeAgo(totals.last_active)}</div>
            </td>
            <td class="text-right">
                <div class="sum">REQ: ${totals.total.toLocaleString()} / WS: ${totals.ws_total.toLocaleString()}</div>
                <div class="sub">2xx: ${totals.st_2xx.toLocaleString()} / 3xx: ${totals.st_3xx.toLocaleString()}</div>
                <div class="sub">4xx: <span style="${t4xxStyle}">${totals.err_4xx.toLocaleString()}</span> / 5xx: <span style="${t5xxStyle}">${totals.err_5xx.toLocaleString()}</span></div>
            </td>
            <td class="text-right">
                <div class="sum">SUM: ${formatBytes(totals.in_t, uTotal)} / ${formatBytes(totals.out_t, uTotal)}</div>
                <div class="sub">HDR: ${formatBytes(totals.in_h, uTotal)} / ${formatBytes(totals.out_h, uTotal)}</div>
                <div class="sub">BDY: ${formatBytes(totals.in_b, uTotal)} / ${formatBytes(totals.out_b, uTotal)}</div>
            </td>
            <td class="text-right">
                <div class="sum">${formatBytes(totals.journal, uTotal)}</div>
            </td>
            <td class="text-right">
                <div class="sum">HTTP: <span style="${tActiveHttpStyle}">${totals.active}</span></div>
                <div class="sub">WS: <span style="${tActiveWsStyle}">${totals.ws_active}</span></div>
            </td>
            <td class="text-right">
                <div class="sum">${globalAvgLat}ms</div>
            </td>
        </tr>`;

    data.route_metrics.forEach(r => {
        const t = r.traffic_flow;
        const s = r.request_statistics;
        const p = r.performance_telemetry;

        const st2xx = s.status_2xx || 0;
        const st3xx = s.status_3xx || 0;
        const err4xx = s.status_4xx || 0;
        const err5xx = s.status_5xx || 0;
        const wsTotal = s.websocket_total || 0;
        const wsActive = s.websocket_active || 0;

        const uRoute = getUnitIndex(Math.max(t.ingress.total_bytes, t.egress.total_bytes, t.journal_storage_bytes));

        // Conditional Styles for Routes
        const r4xxStyle = err4xx > 0 ? 'color: #ffca28; font-weight: 600;' : '';
        const r5xxStyle = err5xx > 0 ? 'color: #ff4444; font-weight: 600;' : '';
        const rActiveHttpStyle = s.active > 0 ? 'color: #00d4ff; font-weight: 600;' : '';
        const rActiveWsStyle = wsActive > 0 ? 'color: #00ff88; font-weight: 600;' : '';

        html += `
        <tr class="clickable-row" onclick="showDetails('${r.id}')">
            <td>
                <div class="sum">${r.id}</div>
                <div class="sub">LAST: ${timeAgo(s.last_active_ts)}</div>
            </td>
            <td class="text-right">
                <div class="sum">REQ: ${s.total.toLocaleString()} / WS: ${wsTotal.toLocaleString()}</div>
                <div class="sub">2xx: ${st2xx.toLocaleString()} / 3xx: ${st3xx.toLocaleString()}</div>
                <div class="sub">4xx: <span style="${r4xxStyle}">${err4xx.toLocaleString()}</span> / 5xx: <span style="${r5xxStyle}">${err5xx.toLocaleString()}</span></div>
            </td>
            <td class="text-right">
                <div class="sum">SUM: ${formatBytes(t.ingress.total_bytes, uRoute)} / ${formatBytes(t.egress.total_bytes, uRoute)}</div>
                <div class="sub">HDR: ${formatBytes(t.ingress.header_bytes, uRoute)} / ${formatBytes(t.egress.header_bytes, uRoute)}</div>
                <div class="sub">BDY: ${formatBytes(t.ingress.body_bytes, uRoute)} / ${formatBytes(t.egress.body_bytes, uRoute)}</div>
            </td>
            <td class="text-right">
                <div class="sum">${formatBytes(t.journal_storage_bytes, uRoute)}</div>
            </td>
            <td class="text-right">
                <div class="sum">HTTP: <span style="${rActiveHttpStyle}">${s.active}</span></div>
                <div class="sub">WS: <span style="${rActiveWsStyle}">${wsActive}</span></div>
            </td>
            <td class="text-right">
                <div class="sum">${(p.average_latency_nanoseconds / 1000000).toFixed(3)}ms</div>
            </td>
        </tr>`;
    });

    view.innerHTML = html;
}

function showDetails(routeId) {
    const config = window.currentConfigs[routeId];
    if (!config) return;

    document.getElementById('route-title').innerText = `${routeId}`;
    document.getElementById('config-upstream').innerText = config.destination || 'N/A';

    document.getElementById('config-journal').innerHTML =
        buildJournalTags('REQ', config.journal?.request || 'NONE') +
        buildJournalTags('RES', config.journal?.response || 'NONE');

    let fHtml = '<div class="tree-view">';
    if (config.filters && config.filters.length > 0) {
        config.filters.forEach((f, i) => {
            const branch = (i === config.filters.length - 1) ? '└─' : '├─';
            fHtml += `<div class="leaf">${branch} ${f.name} <span class="sub">${JSON.stringify(f.args)}</span></div>`;
        });
    } else {
        fHtml += `<div class="leaf">No filters configured.</div>`;
    }
    document.getElementById('config-filters').innerHTML = fHtml + '</div>';

    panel.classList.add('open');
    mainContainer.style.marginRight = '550px';
}

function buildJournalTags(direction, activeLevel) {
    const levels = ['NONE', 'METADATA', 'HEADERS', 'FULL'];
    let html = `<div class="journal-row"><span class="journal-label">${direction}</span>`;

    levels.forEach(lvl => {
        const activeClass = lvl === activeLevel ? ` journal-active-${lvl.toLowerCase()}` : '';
        html += `<span class="journal-tag${activeClass}">${lvl}</span>`;
    });

    html += `</div>`;
    return html;
}

setInterval(update, 1000);
update();