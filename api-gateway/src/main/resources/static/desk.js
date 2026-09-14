'use strict';
const $ = id => document.getElementById(id);
const state = {page: 0, pages: 0, items: [], total: 0, sequence: 0, detailSequence: 0, email: null};
const statuses = {RECEIVED: 'Received', INVESTIGATING: 'Investigating', INVESTIGATED: 'Investigated', INVESTIGATION_FAILED: 'Investigation failed', RESOLVED: 'Resolved'};
function node(tag, value, className) {
    const element = document.createElement(tag);
    if (value != null) element.textContent = value;
    if (className) element.className = className;
    return element;
}
function badge(value) {
    const known = ['CRITICAL','WARNING','INFO', ...Object.keys(statuses)];
    return node('span', statuses[value] || value || 'Unknown',
        'badge ' + (known.includes(value) ? value.toLowerCase() : ''));
}
function date(value) { return value ? new Date(value).toLocaleString() : 'Not recorded'; }
function tool(port, path = '/') {
    const url = new URL(location.href);
    url.port = String(port); url.pathname = path; url.search = ''; url.hash = '';
    return url.href;
}
$('grafana').href = tool(3000);
$('prometheus').href = tool(9090, '/alerts');
async function api(path, options = {}) {
    const response = await fetch(path, {...options, signal: AbortSignal.timeout(12000), cache: 'no-store'});
    if (!response.ok) throw new Error('Request failed (' + response.status + '). Check the service and try again.');
    return response.json();
}
function renderRows() {
    const term = $('search').value.trim().toLowerCase();
    const items = state.items.filter(item => [item.service,item.alertName,item.incidentId,item.title].join(' ').toLowerCase().includes(term));
    $('rows').replaceChildren();
    for (const item of items) {
        const row = node('tr');
        const name = node('td');
        name.append(node('strong', item.alertName), node('small', item.service));
        row.append(name);
        for (const value of [item.severity,item.status]) {
            const cell = node('td'); cell.append(badge(value)); row.append(cell);
        }
        row.append(node('td', date(item.detectedAt)));
        const cell = node('td'), open = node('button', 'View report →');
        open.setAttribute('aria-label', 'View report for ' + item.alertName + ' ' + item.incidentId);
        open.onclick = () => {location.hash = 'incident=' + encodeURIComponent(item.incidentId);};
        cell.append(open); row.append(cell); $('rows').append(row);
    }
    $('empty').hidden = items.length !== 0;
    $('pageInfo').textContent = state.total === 0 ? '0 incidents' :
        'Page ' + (state.page + 1) + ' of ' + state.pages + ' · ' + state.total + ' incidents' +
        (term ? ' · ' + items.length + ' matches on this page' : '');
    $('prev').disabled = state.page === 0;
    $('next').disabled = state.page + 1 >= state.pages;
}
async function refresh() {
    const sequence = ++state.sequence;
    $('refresh').disabled = true;
    try {
        const params = new URLSearchParams({scope:$('scope').value,page:String(state.page),size:$('size').value});
        const page = await api('/api/incidents?' + params);
        if (sequence !== state.sequence) return;
        if (page.totalPages > 0 && state.page >= page.totalPages) {state.page = page.totalPages - 1; return refresh();}
        state.items = page.items; state.total = page.totalItems; state.pages = page.totalPages;
        renderRows(); $('error').hidden = true;
        $('synced').textContent = 'Updated ' + new Date().toLocaleTimeString();
        const counts = await Promise.allSettled(['all','active','resolved'].map(scope =>
            api('/api/incidents?' + new URLSearchParams({scope,size:'1'}))));
        if (sequence !== state.sequence) return;
        counts.forEach((result,index) => {
            $(['allCount','activeCount','resolvedCount'][index]).textContent =
                result.status === 'fulfilled' ? result.value.totalItems : '—';
        });
    } catch (error) {
        if (sequence !== state.sequence) return;
        $('error').textContent = 'Could not refresh incidents. Previously loaded data may be stale. ' + error.message;
        $('error').hidden = false; $('synced').textContent = 'Connection interrupted';
    } finally { if (sequence === state.sequence) $('refresh').disabled = false; }
}
function evidenceGroup(parent, title, values, emptyText) {
    parent.append(node('h3',title));
    if (!values.length) parent.append(node('p',emptyText,'muted'));
    values.forEach(value => parent.append(node('div',value,'evidence')));
}
async function openDetail() {
    const sequence = ++state.detailSequence;
    if (!location.hash.startsWith('#incident=')) {if ($('detail').open) $('detail').close(); return;}
    let id;
    try {id = decodeURIComponent(location.hash.slice(10));} catch {return;}
    if (!$('detail').open) $('detail').showModal();
    $('detailBody').replaceChildren(node('p','Loading report…'));
    try {
        const item = await api('/api/incidents/' + encodeURIComponent(id));
        if (sequence !== state.detailSequence) return;
        const body = $('detailBody'); body.replaceChildren(node('h2',item.title || item.alertName),node('p',item.incidentId,'id'));
        const meta = node('div',null,'detailMeta'); meta.append(badge(item.severity),badge(item.status),node('span',item.service,'badge')); body.append(meta);
        body.append(node('p',item.status === 'RESOLVED' ? 'Recovery webhook received.' :
            'Awaiting recovery. A completed investigation does not mean the service has recovered.','muted'));
        body.append(node('h3','Recorded lifecycle'));
        const timeline=node('div',null,'timeline');
        for (const [label,value] of [['Detected',item.detectedAt],['Last updated',item.updatedAt],['Resolved',item.resolvedAt]]) {
            if (!value) continue;
            const event=node('p',label);event.append(node('small',date(value)));timeline.append(event);
        }
        body.append(timeline,node('p','Only persisted timestamps are shown. Individual queue and worker-stage timestamps are not recorded.','muted'));
        const evidence=item.evidence || [];
        const notes=evidence.filter(value => /unavailable|could not|no telemetry|collection window/i.test(value));
        const metrics=evidence.filter(value => /^[a-z_]+=[-+0-9.]/.test(value));
        const traces=evidence.filter(value => value.startsWith('traceId='));
        const other=evidence.filter(value => !notes.includes(value) && !metrics.includes(value) && !traces.includes(value));
        evidenceGroup(body,'Collection notes',notes,'No collection warnings recorded.');
        evidenceGroup(body,'Metrics snapshot',metrics,'No metric samples saved.');
        evidenceGroup(body,'Error samples and other evidence',other,'No additional samples saved. Check collection notes for source availability.');
        evidenceGroup(body,'Trace summaries',traces,'No trace summaries saved. Generate traced requests and inspect source availability.');
        evidenceGroup(body,'Recommendations',item.recommendations || [],'No recommendations recorded.');
        const actions=node('div',null,'actions'), grafana=node('a','Explore in Grafana ↗');
        grafana.href=tool(3000,'/explore');grafana.target='_blank';grafana.rel='noopener';
        const copy=node('button','Copy incident ID');
        copy.onclick=async()=>{try {await navigator.clipboard.writeText(item.incidentId);copy.textContent='Copied';}catch{copy.textContent='Copy the ID shown above';}};
        const reload=node('button','Refresh report');reload.onclick=openDetail;
        actions.append(grafana,copy,reload);body.append(actions);
    } catch(error) {
        if(sequence!==state.detailSequence)return;
        $('detailBody').replaceChildren(node('p','Unable to load this report. It may have expired through retention. '+error.message,'error'));
    }
}
function renderEmail() {
    const value=state.email;
    $('emailToggle').disabled=!value || (!value.configured && !value.enabled);
    $('emailToggle').setAttribute('aria-checked',String(value?.enabled || false));
    $('emailToggle').textContent=value?.enabled ? 'Email ON · click to turn off' : 'Email OFF · click to turn on';
    $('emailStatus').textContent=!value ? 'Notification service unavailable. Start the optional email profile below.' :
        !value.configured ? 'SMTP setup incomplete. Configure the fields below and recreate the notification service.' :
        value.enabled ? 'Sending enabled for subsequent events. Delivery depends on SMTP.' : 'Sending is muted. The consumer continues processing events.';
}
async function readEmail() {
    $('emailToggle').disabled=true;
    try{state.email=await api('/api/notification-settings');}
    catch{state.email=null;}
    renderEmail();
}
$('emailOpen').onclick=()=>{$('email').showModal();$('emailError').hidden=true;readEmail();};
$('emailToggle').onclick=async()=>{
    if(!state.email)return;
    $('emailToggle').disabled=true;$('emailError').hidden=true;
    try {
        state.email=await api('/api/notification-settings',{method:'PUT',
            headers:{'Content-Type':'application/json','X-Requested-With':'incident-ui'},
            body:JSON.stringify({enabled:!state.email.enabled})});
        renderEmail();
    } catch(error) {
        $('emailError').textContent='Could not confirm the change. '+error.message;
        $('emailError').hidden=false;await readEmail();
    }
};
document.querySelectorAll('[data-close]').forEach(button=>button.onclick=()=>$(button.dataset.close).close());
$('detail').addEventListener('close',()=>{++state.detailSequence;if(location.hash.startsWith('#incident='))history.replaceState(null,'',location.pathname+location.search);});
window.addEventListener('hashchange',openDetail);
$('refresh').onclick=refresh;
$('scope').onchange=$('size').onchange=()=>{state.page=0;refresh();};
$('search').oninput=renderRows;
$('prev').onclick=()=>{if(state.page>0){state.page--;refresh();}};
$('next').onclick=()=>{if(state.page+1<state.pages){state.page++;refresh();}};
document.querySelectorAll('[data-scope]').forEach(button=>button.onclick=()=>{$('scope').value=button.dataset.scope;state.page=0;refresh();});
setInterval(()=>{if($('auto').checked&&!document.hidden&&!$('refresh').disabled){refresh();}},15000);
refresh();openDetail();
