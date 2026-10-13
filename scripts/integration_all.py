"""Executed by validate-all.sh in an isolated lab, using the real HTTP API."""
import concurrent.futures
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = os.environ['CORE_URL']
MINIO = os.environ['MINIO_URL']
STATE = '/tmp/rota-clara-validation.json'


def call(path, method='GET', data=None, role='gestor-local'):
    req = urllib.request.Request(ROOT + '/admin' + path,
        data=json.dumps(data).encode() if data is not None else None, method=method,
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + role})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def ok(path, method='GET', data=None, role='gestor-local'):
    result = call(path, method, data, role)
    assert result['success'], result
    return result['data']


def inventory():
    return {r['location_id'] + '/' + r['product_id']: r['stock'] for r in ok('/inventory')}


def receipt(items):
    return {'items': [{'product_id': p, 'received': n} for p, n in items]}


def create(store, items):
    return ok('/requests', 'POST', {'destination': store,
        'items': [{'product_id': p, 'requested': n} for p, n in items]})['id']


def confirm(request_id, items):
    return ok('/requests/' + request_id + '/confirm', 'POST', receipt(items), 'operador-local')


def document(request_id):
    return ok('/requests/' + request_id + '/document')


def wait_for(fetch, predicate, timeout=60):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            last = fetch()
            if predicate(last): return last
        except (urllib.error.URLError, ConnectionError, TimeoutError): pass
        time.sleep(0.25)
    raise AssertionError({'timeout': timeout, 'last': last})


def mode(value):
    ok('/lab/fiscal-mode', 'PUT', {'mode': value})


def need(store, product):
    return next((r['need'] for r in ok('/needs?store=' + store) if r['product_id'] == product), 0)


def concurrent_confirm(request_id, items):
    barrier = threading.Barrier(2)
    def task():
        barrier.wait(timeout=10)
        return confirm(request_id, items)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(task) for _ in range(2)]
        results = [f.result(timeout=25) for f in futures]
    assert results[0] == results[1], results
    return results[0]


def save(state):
    with open(STATE, 'w') as f: json.dump(state, f)


phase = sys.argv[1]
if phase == 'initial':
    wait_for(lambda: ok('/session'), lambda v: v['role'] == 'MANAGER')
    state = {'histories': ['H01', 'H02', 'H03', 'H04', 'H05'], 'results': {}}
    start = inventory()
    assert start['loja-aurora/agua-500'] == 4 and start['loja-lagoa/cafe-250'] == 7
    assert need('loja-aurora', 'agua-500') == 6
    assert need('loja-aurora', 'arroz-1000') == 0
    assert need('loja-lagoa', 'cafe-250') == 0
    for store, product, quantity in [('loja-lagoa', 'cafe-250', 1), ('loja-aurora', 'arroz-1000', 1), ('loja-aurora', 'agua-500', 7)]:
        invalid = call('/requests', 'POST', {'destination': store, 'items': [{'product_id': product, 'requested': quantity}]})
        assert not invalid['success'] and invalid['status'] == 400, invalid
    assert inventory() == start and ok('/requests') == []
    state['results']['h01'] = {'below_need': 6, 'equal_need': 0, 'above_need': 0, 'invalid_orders_created': 0}
    ok('/lab/cache-enabled', 'PUT', {'enabled': False})
    before = ok('/lab/cache-metrics')
    for _ in range(3): assert need('loja-aurora', 'agua-500') == 6
    after = ok('/lab/cache-metrics')
    assert after['needs_db_queries'] - before['needs_db_queries'] == 3
    ok('/lab/cache-enabled', 'PUT', {'enabled': True})
    # Use another store which was not warmed during the baseline, for identical before/after requests.
    ok('/lab/cache-enabled', 'PUT', {'enabled': False})
    b0 = ok('/lab/cache-metrics')
    for _ in range(3): assert need('loja-lagoa', 'agua-500') == 6
    b1 = ok('/lab/cache-metrics')
    ok('/lab/cache-enabled', 'PUT', {'enabled': True})
    c0 = ok('/lab/cache-metrics')
    # Lagoa was touched by H01, so its existing v0 entry is legitimately warm.
    for _ in range(3): assert need('loja-lagoa', 'agua-500') == 6
    c1 = ok('/lab/cache-metrics')
    assert b1['needs_db_queries'] - b0['needs_db_queries'] == 3
    assert c1['needs_db_queries'] - c0['needs_db_queries'] <= 1
    assert c1['cache_hits'] - c0['cache_hits'] >= 2
    assert c1['version_db_queries'] - c0['version_db_queries'] == 3
    state['results']['h05_comparison'] = {
        'same_store_requests': 3, 'without_cache_needs_queries': 3,
        'with_cache_needs_queries': c1['needs_db_queries'] - c0['needs_db_queries'],
        'cache_hits': c1['cache_hits'] - c0['cache_hits'], 'version_reads': 3,
        'note': 'Version lookups remain; savings apply to the stock/product join, not all SQL statements.'}
    denied = call('/lab/cache-metrics', role='operador-local')
    assert not denied['success'] and denied['status'] == 403
    mode('indisponivel')
    denied = call('/requests', 'POST', {'destination': 'loja-aurora', 'items': [{'product_id': 'agua-500', 'requested': 6}]}, 'operador-local')
    assert not denied['success'] and denied['status'] == 403
    request_id = create('loja-aurora', [('agua-500', 6), ('cafe-250', 2)])
    items = [('agua-500', 4), ('cafe-250', 0)]
    for water, coffee in [(-1, 0), (7, 0), (4, 3), (0, 0)]:
        invalid = call('/requests/' + request_id + '/confirm', 'POST', receipt([('agua-500', water), ('cafe-250', coffee)]), 'operador-local')
        assert not invalid['success'] and invalid['status'] == 400
        assert inventory() == start
    result = concurrent_confirm(request_id, items)
    assert result['status'] == 'CONFIRMED'
    assert [(i['requested'], i['received'], i['missing']) for i in result['items']] == [(6, 4, 2), (2, 0, 2)]
    assert inventory()['centro-horizonte/agua-500'] == 96 and inventory()['loja-aurora/agua-500'] == 8
    assert need('loja-aurora', 'agua-500') == 2 and need('loja-lagoa', 'agua-500') == 6
    pending = wait_for(lambda: document(request_id), lambda d: d['status'] == 'RETRY_WAIT', 20)
    state.update({'partial_request': request_id, 'partial_items': items, 'confirmed_stock': inventory(), 'partial_result': result})
    state['results']['h02_h03'] = {'identical_concurrent_calls': 2, 'center_water': 96, 'aurora_water': 8,
        'water': [6, 4, 2], 'coffee': [2, 0, 2]}
    state['results']['h04_unavailable'] = {'receipt': 'CONFIRMED', 'document': pending['status'], 'attempts': pending['attempts']}
    save(state)
else:
    with open(STATE) as f: state = json.load(f)
    request_id = state['partial_request']
    if phase == 'authorized_without_xml':
        mode('sucesso')
        if document(request_id)['status'] in ('RETRY_WAIT', 'FAILED'):
            ok('/requests/' + request_id + '/document/reprocess', 'POST')
        interrupted = wait_for(lambda: document(request_id), lambda d: d['status'] == 'RETRY_WAIT' and d['protocol'] is not None, 25)
        assert interrupted['object_key'] is None
        assert inventory() == state['confirmed_stock']
        state['results']['h04_restart_before'] = {'document': interrupted['status'], 'protocol': interrupted['protocol'], 'xml_stored': False}
    elif phase == 'after_restart':
        final = wait_for(lambda: document(request_id), lambda d: d['status'] == 'AUTHORIZED_SIMULATION')
        assert inventory() == state['confirmed_stock']
        assert confirm(request_id, state['partial_items']) == state['partial_result']
        divergent = call('/requests/' + request_id + '/confirm', 'POST', receipt([('agua-500', 3), ('cafe-250', 0)]), 'operador-local')
        assert not divergent['success'] and divergent['status'] == 409
        assert document(request_id) == final
        state['results']['h04_restart_after'] = {'document': final['status'], 'same_protocol': final['protocol'] == state['results']['h04_restart_before']['protocol'], 'stock_unchanged': True}
        assert state['results']['h04_restart_after']['same_protocol']
        mode('resposta-perdida')
        lost_id = create('loja-lagoa', [('agua-500', 6)])
        confirm(lost_id, [('agua-500', 1)])
        lost = wait_for(lambda: document(lost_id), lambda d: d['status'] == 'AUTHORIZED_SIMULATION')
        assert lost['attempts'] >= 2
        assert lost['document_key'] == 'doc-' + lost_id
        state['lost_request'] = lost_id
        state['results']['h04_lost_response'] = {'document_key': lost['document_key'], 'attempts': lost['attempts'], 'document': lost['status']}
        mode('rejeicao')
        rejected_id = create('loja-aurora', [('cafe-250', 2)])
        confirm(rejected_id, [('cafe-250', 1)])
        rejected = wait_for(lambda: document(rejected_id), lambda d: d['status'] == 'REJECTED_SIMULATION')
        time.sleep(2)
        assert document(rejected_id)['attempts'] == 1
        assert rejected['reason']
        state['results']['h04_rejection'] = {'document': rejected['status'], 'attempts': rejected['attempts'], 'reason': rejected['reason']}
    elif phase == 'redis_down':
        before = ok('/lab/cache-metrics')
        assert need('loja-aurora', 'agua-500') == 2
        mode('indisponivel')
        limited_id = create('loja-lagoa', [('arroz-1000', 5)])
        confirm(limited_id, [('arroz-1000', 1)])
        assert need('loja-lagoa', 'arroz-1000') == 4
        after = ok('/lab/cache-metrics')
        assert after['cache_errors'] > before['cache_errors']
        failed = wait_for(lambda: document(limited_id), lambda d: d['status'] == 'FAILED', 35)
        assert failed['attempts'] == 3, failed
        state['limited_request'] = limited_id
        state['limited_stock'] = inventory()
        state['results']['h05_redis_down'] = {'query_succeeded': True, 'receipt_succeeded': True, 'updated_need': 4, 'cache_errors_delta': after['cache_errors'] - before['cache_errors']}
        state['results']['h04_attempt_limit'] = {'document': failed['status'], 'attempts': failed['attempts']}
    elif phase == 'redis_up':
        assert need('loja-lagoa', 'arroz-1000') == 4
        # Allow Lettuce to reconnect; fallback remains valid while reconnection is pending.
        previous_hits = ok('/lab/cache-metrics')['cache_hits']
        wait_for(lambda: (ok('/needs?store=loja-lagoa'), ok('/lab/cache-metrics')),
            lambda pair: pair[1]['cache_hits'] > previous_hits, 15)
        mode('sucesso')
        limited_id = state['limited_request']
        denied = call('/requests/' + limited_id + '/document/reprocess', 'POST', role='operador-local')
        assert not denied['success'] and denied['status'] == 403
        ok('/requests/' + limited_id + '/document/reprocess', 'POST')
        final = wait_for(lambda: document(limited_id), lambda d: d['status'] == 'AUTHORIZED_SIMULATION')
        assert final['attempts'] == 4 and inventory() == state['limited_stock']
        state['results']['h04_manual_reprocess'] = {'document': final['status'], 'attempts': final['attempts'], 'stock_unchanged': True}
        with urllib.request.urlopen(urllib.request.Request(ROOT + '/admin/requests/' + limited_id + '/document/xml', headers={'Authorization': 'Bearer operador-local'}), timeout=20) as r:
            assert b'SIMULACAO' in r.read()
        with urllib.request.urlopen(ROOT + '/admin/requests/' + limited_id + '/document/xml', timeout=20) as r:
            assert json.load(r)['status'] == 401
        try:
            urllib.request.urlopen(MINIO + '/rota-clara-validation-documents/' + final['object_key'], timeout=10)
            raise AssertionError('XML publicamente acessível')
        except urllib.error.HTTPError as e: assert e.code == 403, e.code
        state['results']['h04_xml'] = {'authorized_api': True, 'unauthenticated_api_status': 401, 'private_minio_status': 403}
        state['results']['h05_final_metrics'] = ok('/lab/cache-metrics')
        print(json.dumps(state['results'], ensure_ascii=False, indent=2))
    else: raise SystemExit('Fase desconhecida')
    save(state)
print('Fase ' + phase + ': OK', file=sys.stderr)
