import json,time,os,urllib.request
checks={'core':'http://localhost:18080/actuator/health','fiscal':'http://localhost:18090/health','minio':'http://localhost:19000/minio/health/live','eureka':'http://localhost:18761/actuator/health','admin':'http://localhost:13000/admin/session'}
if os.path.exists('/.dockerenv'):
 checks={'core':'http://core:8080/actuator/health','fiscal':'http://fiscal-simulado:8090/health','minio':'http://minio:9000/minio/health/live','eureka':'http://eureka:8761/actuator/health','admin':'http://admin/admin/session'}
remaining=dict(checks);deadline=time.time()+180
while remaining and time.time()<deadline:
 for name,url in list(remaining.items()):
  try:
   with urllib.request.urlopen(urllib.request.Request(url,headers={'Authorization':'Bearer gestor-local'}),timeout=3) as r:
    payload=r.read()
    if name=='admin' and not json.loads(payload).get('success'):continue
    if name in ['core','eureka','fiscal'] and json.loads(payload).get('status')!='UP':continue
   print(name+': ready');del remaining[name]
  except Exception: pass
 if remaining:time.sleep(2)
if remaining:raise SystemExit('Not ready: '+', '.join(remaining))
print('LAB READY — http://localhost:13000')
