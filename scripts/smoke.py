import json, os, time, urllib.request
ROOT=os.environ.get('CORE_URL','http://core:8080' if os.path.exists('/.dockerenv') else 'http://localhost:18080')
def call(path,method='GET',data=None,token='gestor-local'):
 r=urllib.request.Request(ROOT+path,data=json.dumps(data).encode() if data is not None else None,method=method,headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
 with urllib.request.urlopen(r,timeout=30) as response: return json.load(response)
assert call('/admin/session')['data']['role']=='MANAGER'
assert len(call('/admin/inventory')['data'])==9
assert call('/admin/needs?store=loja-aurora')['data'][0]['need']==6
request=call('/admin/requests','POST',{'destination':'loja-aurora','items':[{'product_id':'agua-500','requested':6},{'product_id':'cafe-250','requested':2}]})['data']
result=call('/admin/requests/'+str(request['id'])+'/confirm','POST',{'items':[{'product_id':'agua-500','received':4},{'product_id':'cafe-250','received':2}]},'operador-local')
assert result['success'],result
assert result['data']['status']=='CONFIRMED'
deadline=time.monotonic()+45
while True:
 document=call('/admin/requests/'+str(request['id'])+'/document')['data']
 if document['status']=='AUTHORIZED_SIMULATION': break
 assert document['status'] not in ('FAILED','REJECTED_SIMULATION'),document
 assert time.monotonic()<deadline,document
 time.sleep(0.25)
assert document['status']=='AUTHORIZED_SIMULATION',document
with urllib.request.urlopen(urllib.request.Request(ROOT+'/admin/requests/'+str(request['id'])+'/document/xml',headers={'Authorization':'Bearer gestor-local'})) as r:
 assert b'SIMULACAO' in r.read()
print('SMOKE OK: session, inventory, needs, creation, partial confirmation, fiscal and stored XML')
