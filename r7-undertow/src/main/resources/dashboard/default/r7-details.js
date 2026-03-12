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