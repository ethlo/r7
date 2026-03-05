const view = document.getElementById('view');
const vitals = document.getElementById('sys-vitals');
const timeEl = document.getElementById('last-update');
const panel = document.getElementById('details-panel');

let routeConfigs = {}; // Store static configs from the last fetch

function formatUptime(ms) {
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const h = Math.floor(m / 60);
    const d = Math.floor(h / 24);
    return `${d}d ${h % 24}h ${m % 60}m ${s % 60}s`;
}

function showDetails(routeId) {
    const config = routeConfigs[routeId] || { upstream: 'N/A', filters: 'N/A', journal: 'N/A' };
    document.getElementById('route-title').innerHTML = `${routeId} <span>CONFIGURATION</span>`;
    document.getElementById('config-upstream').innerText = config.upstream;
    document.getElementById('config-filters').innerText = config.filters;
    document.getElementById('config-journal').innerText = config.journal;

    panel.classList.add('open');
    document.getElementById('main-container').style.marginRight = '500px';
}

function formatBytes(b) {
    if (b === 0) return '0 B';
    const i = Math.floor(Math.log(b) / Math.log(1024));
    return (b / Math.pow(1024, i)).toFixed(2) + ' ' + ['B', 'KB', 'MB', 'GB'][i];
}

function closeDetails() {
    panel.classList.remove('open');
    document.getElementById('main-container').style.marginRight = 'auto';
}

async function update() {
    try {
        const res = await fetch(window.location.href, { headers: {'Accept': 'application/json'} });
        const data = await res.json();
        routeConfigs = data.configs;

        vitals.innerText = `UPTIME: ${formatUptime(data.system.uptime_ms)}`;

        const totals = data.routes.reduce((acc, r) => ({
            total: acc.total + r.total,
            active: acc.active + r.active,
            h_in: acc.h_in + r.h_in,
            b_in: acc.b_in + r.b_in,
            h_out: acc.h_out + r.h_out,
            b_out: acc.b_out + r.b_out,
            journal: acc.journal + r.journal_bytes,
            latency: acc.latency + (r.avg_latency_ns * r.total)
        }), { total: 0, active: 0, h_in: 0, b_in: 0, h_out: 0, b_out: 0, journal: 0, latency: 0 });

        const globalAvgLat = totals.total === 0 ? 0 : (totals.latency / totals.total / 1000000).toFixed(3);

        // --- THE SUMMARY BLADE (Top Row) ---
        let html = `
            <tr class="total-row">
                <td><div class="val">SYSTEM TOTALS</div><div class="unit">REQS: ${totals.total.toLocaleString()}</div></td>
                <td>
                    <div class="val">${formatBytes(totals.h_in + totals.b_in)} <span class="unit">IN</span></div>
                    <div class="val">${formatBytes(totals.h_out + totals.b_out)} <span class="unit">OUT</span></div>
                </td>
                <td class="latency">${formatBytes(totals.journal)}<span class="unit">WRITTEN</span></td>
                <td><span class="val">${totals.active}</span></td>
                <td class="val">${globalAvgLat}ms</td>
            </tr>`;

        // --- INDIVIDUAL ROUTE BLADES ---
        data.routes.forEach(r => {
            html += `
            <tr onclick="showDetails('${r.id}')">
                <td>
                    <div class="val">${r.id}</div>
                    <div class="unit">REQS: ${r.total.toLocaleString()}</div>
                </td>
                <td>
                    <div>
                        <span class="val">${formatBytes(r.h_in + r.b_in)}</span> <span class="unit">IN</span>
                        <span class="dim-small">(${formatBytes(r.h_in)}H / ${formatBytes(r.b_in)}B)</span>
                    </div>
                    <div style="margin-top:2px;">
                        <span class="val">${formatBytes(r.h_out + r.b_out)}</span> <span class="unit">OUT</span>
                        <span class="dim-small">(${formatBytes(r.h_out)}H / ${formatBytes(r.b_out)}B)</span>
                    </div>
                </td>
                <td class="latency">${formatBytes(r.journal_bytes)}<span class="unit">WRITTEN</span></td>
                <td><span style="color: ${r.active > 0 ? 'var(--accent)' : 'inherit'}">${r.active}</span></td>
                <td class="val">${(r.avg_latency_ns / 1000000).toFixed(3)}ms</td>
            </tr>`;
        });
        view.innerHTML = html;
    } catch (e) { timeEl.innerText = 'OFFLINE'; }
}

setInterval(update, 500);
update();