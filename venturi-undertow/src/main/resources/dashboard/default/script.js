const view = document.getElementById('view');
const vitals = document.getElementById('sys-vitals');
const timeEl = document.getElementById('last-update');
const panel = document.getElementById('details-panel');

function formatBytes(b) {
    if (b === 0) return '0 B';
    const i = Math.floor(Math.log(b) / Math.log(1024));
    return (b / Math.pow(1024, i)).toFixed(2) + ' ' + ['B', 'KB', 'MB', 'GB'][i];
}

function showDetails(routeId) {
    document.getElementById('route-title').innerText = routeId;
    panel.classList.add('open');
    document.getElementById('main-container').style.marginRight = '470px';
}

function closeDetails() {
    panel.classList.remove('open');
    document.getElementById('main-container').style.marginRight = 'auto';
}

async function update() {
    try {
        const res = await fetch(window.location.href, {
            headers: { 'Accept': 'application/json' }
        });
        const data = await res.json();

        timeEl.innerText = new Date().toLocaleTimeString();

        let html = '';
        data.routes.forEach(r => {
            html += `
            <tr onclick="showDetails('${r.id}')">
                <td class="val">${r.id}</td>
                <td>${formatBytes(r.bytes_in)}<span class="unit">IN</span> / ${formatBytes(r.bytes_out)}<span class="unit">OUT</span></td>
                <td class="latency">${formatBytes(r.journal_bytes)}<span class="unit">WRITTEN</span></td>
                <td><span style="color: ${r.active > 0 ? 'var(--accent)' : 'inherit'}">${r.active}</span></td>
                <td class="val">${(r.avg_latency_ns / 1000000).toFixed(3)}ms</td>
            </tr>`;
        });
        view.innerHTML = html;

        // Placeholder for global vitals calculation
        vitals.innerText = `ROUTES: ${data.routes.length} | UPDATING...`;

    } catch (e) {
        timeEl.innerText = 'OFFLINE';
    }
}

setInterval(update, 2000);
update();