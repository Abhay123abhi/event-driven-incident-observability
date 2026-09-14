// Browser contract checks use synthetic responses; no real alerts or emails are sent.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
(async () => {
    const browser = await chromium.launch({headless:true});
    try {
        const page = await browser.newPage({viewport:{width:1440,height:1100}});
        const failures=[]; page.on('pageerror',error=>failures.push(error.message));
        let enabled=false;
        const incident={incidentId:'test-incident',service:'inventory-service',alertName:'HighResponseLatency',
            status:'INVESTIGATED',severity:'WARNING',title:'Inventory latency',
            evidence:['p95_latency_seconds=3','Loki unavailable: error logs could not be collected.',
                '<script>window.injected=true</script>'],
            recommendations:['Inspect downstream calls'],detectedAt:'2026-09-14T12:00:00Z',
            updatedAt:'2026-09-14T12:01:00Z',resolvedAt:null};
        await page.route('**/api/**',async route=>{
            const url=new URL(route.request().url());
            if(url.pathname==='/api/notification-settings'){
                if(route.request().method()==='PUT'){
                    assert.equal(route.request().headers()['x-requested-with'],'incident-ui');
                    enabled=route.request().postDataJSON().enabled;
                }
                return route.fulfill({json:{enabled,configured:true,startupEnabled:false}});
            }
            if(url.pathname==='/api/incidents/test-incident')return route.fulfill({json:incident});
            const resolved=url.searchParams.get('scope')==='resolved';
            return route.fulfill({json:{items:resolved?[]:[incident],page:0,size:20,totalItems:resolved?0:1,totalPages:resolved?0:1}});
        });
        await page.goto('http://127.0.0.1:8765');
        await page.getByRole('button',{name:/View report for/}).waitFor();
        await page.screenshot({path:'/tmp/incident-desktop.png',fullPage:true});
        await page.getByRole('button',{name:/View report for/}).click();
        await page.getByText('Loki unavailable: error logs could not be collected.',{exact:true}).waitFor();
        assert.equal(await page.evaluate(()=>window.injected),undefined);
        await page.screenshot({path:'/tmp/incident-detail.png',fullPage:true});
        await page.getByRole('button',{name:'Close incident details'}).click();
        await page.getByRole('button',{name:'Email settings',exact:true}).click();
        await page.getByRole('switch').waitFor();
        await page.waitForFunction(()=>!document.getElementById('emailToggle').disabled);
        await page.getByRole('switch').click();
        await page.waitForFunction(()=>document.getElementById('emailToggle').getAttribute('aria-checked')==='true');
        assert.equal(enabled,true);
        await page.getByRole('switch').click();
        await page.waitForFunction(()=>document.getElementById('emailToggle').getAttribute('aria-checked')==='false');
        await page.getByRole('button',{name:'Close email settings'}).click();
        await page.locator('#scope').selectOption('resolved');
        await page.locator('#empty').waitFor({state:'visible'});
        await page.locator('#scope').selectOption('all');
        await page.getByRole('button',{name:/View report for/}).waitFor();
        await page.setViewportSize({width:390,height:844});
        await page.screenshot({path:'/tmp/incident-mobile.png',fullPage:true});
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
        // Unavailable email service is not displayed as successfully muted.
        await page.route('**/api/notification-settings',route=>route.fulfill({status:503,body:'Unavailable'}));
        await page.getByRole('button',{name:'Email settings',exact:true}).click();
        await page.getByText(/Notification service unavailable/).waitFor();
        assert.equal(await page.getByRole('switch').isDisabled(),true);
        await page.getByRole('button',{name:'Close email settings'}).click();
        await page.route('**/api/incidents?*',route=>route.fulfill({status:503,body:'Unavailable'}));
        await page.locator('#refresh').click();
        await page.locator('#error').waitFor({state:'visible'});
        assert.deepEqual(failures,[]);
        console.log('PASS: list, details, safe evidence text, email toggle, empty/error states and mobile width');
    } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
