// UI contract regression with deterministic API fixtures; backend uses separate real integration tests.
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const watchdog = setTimeout(() => { console.error('Tempo máximo do teste da interface excedido (90s).'); process.exit(1); }, 90000);
watchdog.unref();
(async () => {
  const browser = await chromium.launch({executablePath:'/usr/bin/chromium', timeout:30000, args:['--no-sandbox','--disable-dev-shm-usage']});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    let confirmed = false, docStatus = 'NOT_REQUESTED', polls = 0, retries = 0, cacheEnabled = true;
    const id = 'ui-regression-request';
    const products = [['agua-500','Água 500 ml',6,4],['cafe-250','Café 250 g',2,0]];
    const detail = () => ({id,destination:'loja-aurora',origin:'centro-horizonte',status:confirmed?'CONFIRMED':'OPEN',
      confirmed_by:confirmed?'OPERATOR':null,items:products.map(([product_id,product_name,requested,amount]) => ({
        product_id,product_name,requested,received:confirmed?amount:null,missing:requested-(confirmed?amount:0)}))});
    const doc = () => ({status:docStatus,attempts:confirmed?2:0,protocol:confirmed?'SIM-ui':null,
      reason:docStatus==='FAILED'?'Falha temporária no armazenamento':null});
    await page.route('**/admin/**', async route => {
      const req = route.request(), path = new URL(req.url()).pathname;
      let data;
      if (path.endsWith('/inventory')) data = [];
      else if (path.endsWith('/needs')) data = [];
      else if (path.endsWith('/session')) data = {role:'MANAGER'};
      else if (path.endsWith('/cache-metrics')) data = {cache_enabled:cacheEnabled,cache_hits:2,cache_misses:1,cache_errors:0,needs_db_queries:1,version_db_queries:3,ttl_seconds:60};
      else if (path.endsWith('/cache-enabled')) {
        cacheEnabled = req.postDataJSON().enabled;
        data = {cache_enabled:cacheEnabled,cache_hits:2,cache_misses:1,cache_errors:0,needs_db_queries:1,version_db_queries:3,ttl_seconds:60};
      } else if (path.endsWith('/document/reprocess')) {
        retries++; docStatus='PENDING'; polls=0; data=doc();
      } else if (path.endsWith('/document/xml')) {
        await route.fulfill({status:200,contentType:'application/xml',body:'<Transferencia ambiente="SIMULACAO"/>'}); return;
      } else if (path.endsWith('/document')) {
        if (docStatus==='PENDING' && ++polls>=3) docStatus='AUTHORIZED_SIMULATION';
        data=doc();
      } else if (path.endsWith('/confirm')) {
        assert.deepEqual(req.postDataJSON(),{items:[{product_id:'agua-500',received:4},{product_id:'cafe-250',received:0}]});
        confirmed=true; docStatus='PENDING'; polls=0; data=detail();
      } else if (path.endsWith('/requests')) data=[detail()];
      else if (path.endsWith('/'+id)) data=detail();
      else throw Error('Rota inesperada: '+path);
      await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({success:true,status:200,data,message:null})});
    });
    await page.goto(process.env.ADMIN_URL || 'http://127.0.0.1:13000');
    await page.getByLabel('Perfil').selectOption('operador-local');
    await page.locator('.request-row').click();
    await page.getByLabel('Recebido Água 500 ml').fill('4');
    await page.getByLabel('Recebido Café 250 g').fill('0');
    await page.getByRole('button',{name:'Confirmar entrega',exact:true}).click();
    const confirmedButton=page.getByRole('button',{name:'Entrega confirmada',exact:true});
    await confirmedButton.waitFor(); assert(await confirmedButton.isDisabled());
    assert(await page.getByLabel('Recebido Água 500 ml').isDisabled());
    const water=page.locator('section').filter({has:page.getByRole('heading',{name:'Conferência da entrega'})})
      .locator('tbody tr').filter({hasText:'Água 500 ml'});
    assert.equal(await water.locator('td').nth(1).innerText(),'6');
    assert.equal(await water.locator('input').inputValue(),'4');
    assert.equal(await water.locator('td').nth(3).innerText(),'2');
    await page.getByText('Autorizado · XML disponível',{exact:true}).waitFor();
    await page.getByText('Protocolo do provedor: SIM-ui',{exact:true}).waitFor();
    assert.equal(await water.locator('input').inputValue(),'4');
    docStatus='FAILED';
    await page.getByRole('button',{name:'Atualizar',exact:true}).click();
    await page.getByText('Falha · reprocessamento disponível',{exact:true}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Tentar emissão novamente',exact:true}).count(),0);
    await page.getByLabel('Perfil').selectOption('gestor-local');
    await page.getByRole('button',{name:'Tentar emissão novamente',exact:true}).click();
    await page.getByText('Autorizado · XML disponível',{exact:true}).waitFor();
    assert.equal(retries,1); assert(await confirmedButton.isDisabled());
    const downloadPromise=page.waitForEvent('download');
    await page.getByRole('button',{name:'Baixar XML demonstrativo',exact:true}).click();
    assert.equal((await downloadPromise).suggestedFilename(),id+'.xml');
    await page.getByRole('button',{name:'Consultar contadores',exact:true}).click();
    await page.getByRole('button',{name:'Desativar cache para comparação',exact:true}).click();
    await page.getByRole('button',{name:'Ativar cache',exact:true}).waitFor();
    assert.equal(cacheEnabled,false);
    console.log('UI OK: histórico 6/4/2, confirmação bloqueada, emissão atualizada automaticamente, protocolo, reprocessamento só do Gestor, XML e contadores.');
  } finally { await browser.close(); clearTimeout(watchdog); }
})().catch(e=>{console.error(e);process.exit(1)});
