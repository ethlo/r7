const FRACTION_DIGITS = 2;
const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

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

function formatCompact(num) {
    if (num === 0 || num == null) return '0';
    return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(num);
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

const mRow = (label, primaryValue, isSum = false, valueClass = '', rateValue = null) => {
    const valContainer = rateValue !== null
        ? `<div style="display: grid; grid-template-columns: 60px 60px; gap: 8px; text-align: right; font-variant-numeric: tabular-nums;">
             <span class="${valueClass}">${primaryValue}</span>
             <span class="${valueClass}">${rateValue}<span class="text-dim" style="opacity: 0.5;"></span></span>
           </div>`
        : `<div style="display: grid; grid-template-columns: 128px; text-align: right; font-variant-numeric: tabular-nums;">
             <span class="${valueClass}">${primaryValue}</span>
           </div>`;

    return `
    <div class="${isSum ? 'sum' : 'sub'} metric-row" style="display: flex; align-items: baseline;">
        <span class="metric-label">${label}</span>
        <span class="metric-leader" style="flex-grow: 1; border-bottom: 1px dotted rgba(128,128,128,0.3); margin: 0 8px;"></span>
        <div class="metric-value">
            ${valContainer}
        </div>
    </div>`;
};

const jBadge = (lvl) => {
    const l = (lvl || 'NONE').toUpperCase();
    return `<span class="journal-active-${l.toLowerCase()} j-badge">${l.charAt(0)}</span>`;
};

function buildFilterPipelineHtml(node, destination) {
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

    // Inject the actual destination here
    const targetDisplay = destination ? destination : phases.core;

    html += `<div style="margin-bottom: 12px;">`;
    html += `<div class="sub text-dim" style="border-bottom: 1px solid rgba(128,128,128,0.2); margin-bottom: 4px; padding-bottom: 2px;">◆ TARGET</div>`;
    html += `<div class="sub" style="padding-left: 4px; opacity: 0.9;"><span class="text-dim" style="margin-right: 4px;">└─</span><span class="text-http">${targetDisplay}</span></div>`;
    html += `</div>`;

    html += renderPhase('CLIENT RESPONSE', phases.clientRes, '↑');
    html += renderPhase('COMPLETED', phases.completed, '↑');

    html += '</div>';

    return html;
}

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

    let maxValue = 0;
    for (let i = 0; i < len; i++) {
        const s = Number(successData[i]) || 0;
        const c = Number(clientData[i]) || 0;
        const e = Number(serverData[i]) || 0;
        maxValue = Math.max(maxValue, s, c, e);
    }

    ctx.fillStyle = 'rgba(255, 255, 255, 0.05)';
    ctx.fillRect(0, height - 1, width, 1);

    if (maxValue === 0) return;

    const stepX = width / Math.max(1, len - 1);

    const drawLine = (dataArray, color) => {
        ctx.beginPath();
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.5;
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';

        let isDrawing = false;

        for (let i = 0; i < len; i++) {
            const val = Number(dataArray[i]) || 0;

            if (val === 0) {
                isDrawing = false;
                continue;
            }

            const y = Math.max(1, height - ((val / maxValue) * height));
            const x = i * stepX;

            if (!isDrawing) {
                ctx.moveTo(x, y);
                ctx.lineTo(x + 0.1, y);
                isDrawing = true;
            } else {
                ctx.lineTo(x, y);
            }
        }
        ctx.stroke();
    };

    const COLOR_SUCCESS = '#2ecc71';
    const COLOR_CLIENT_ERR = '#f39c12';
    const COLOR_SERVER_ERR = '#e74c3c';

    drawLine(successData, COLOR_SUCCESS);
    drawLine(clientData, COLOR_CLIENT_ERR);
    drawLine(serverData, COLOR_SERVER_ERR);
}