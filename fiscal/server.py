import json, os, sqlite3, socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape
DB=os.environ.get('STATE_FILE','/data/fiscal.db')
os.makedirs(os.path.dirname(DB),exist_ok=True)
with sqlite3.connect(DB) as c:
 c.execute('CREATE TABLE IF NOT EXISTS documents (key TEXT PRIMARY KEY,payload TEXT,response TEXT,xml TEXT)')
 c.execute('CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY,mode TEXT)')
 c.execute("INSERT OR IGNORE INTO settings VALUES(1,'sucesso')")
class Handler(BaseHTTPRequestHandler):
 def body(self):
  if self.headers.get('Transfer-Encoding','').lower()=='chunked':
   chunks=[]
   while True:
    size=int(self.rfile.readline().split(b';',1)[0].strip(),16)
    if size==0:
     while self.rfile.readline().strip(): pass
     return b''.join(chunks)
    chunks.append(self.rfile.read(size))
    self.rfile.read(2)
  return self.rfile.read(int(self.headers.get('Content-Length',0)))
 def send(self,status,value,content_type='application/json'):
  data=json.dumps(value).encode() if content_type=='application/json' else value.encode()
  self.send_response(status); self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def do_GET(self):
  if self.path=='/health': return self.send(200,{'status':'UP'})
  if self.path=='/admin/mode':
   with sqlite3.connect(DB) as c: mode=c.execute('SELECT mode FROM settings').fetchone()[0]
   return self.send(200,{'mode':mode})
  if self.path.startswith('/documents/'):
   key=self.path.split('/')[2]
   with sqlite3.connect(DB) as c: row=c.execute('SELECT response,xml FROM documents WHERE key=?',(key,)).fetchone()
   if not row: return self.send(404,{'message':'Documento desconhecido'})
   if self.path.endswith('/xml'):
    if not row[1]: return self.send(409,{'message':'Documento não autorizado'})
    return self.send(200,row[1],'application/xml')
   return self.send(200,json.loads(row[0]))
  self.send(404,{'message':'Rota desconhecida'})
 def do_PUT(self):
  if self.path!='/admin/mode': return self.send(404,{'message':'Rota desconhecida'})
  try: mode=json.loads(self.body())['mode']
  except (ValueError,KeyError): return self.send(400,{'message':'Modo inválido'})
  if mode not in ['sucesso','rejeicao','indisponivel','resposta-perdida']: return self.send(400,{'message':'Modo inválido'})
  with sqlite3.connect(DB) as c: c.execute('UPDATE settings SET mode=? WHERE id=1',(mode,))
  self.send(200,{'mode':mode})
 def do_POST(self):
  if self.path!='/documents': return self.send(404,{'message':'Rota desconhecida'})
  try:
   payload=json.loads(self.body())
   key=payload['document_key'];canonical=json.dumps(payload,sort_keys=True)
   with sqlite3.connect(DB,timeout=15) as c:
    c.execute('BEGIN IMMEDIATE')
    row=c.execute('SELECT payload,response FROM documents WHERE key=?',(key,)).fetchone()
    if row:
     if row[0]!=canonical: return self.send(409,{'message':'Chave usada com outros dados'})
     return self.send(200,json.loads(row[1]))
    mode=c.execute('SELECT mode FROM settings WHERE id=1').fetchone()[0]
    if mode=='indisponivel': return self.send(503,{'message':'Simulador indisponível'})
    xml=''
    if mode=='rejeicao': response={'document_key':key,'status':'REJECTED_SIMULATION','reason':'Dados rejeitados no cenário de laboratório'}
    else:
     response={'document_key':key,'status':'AUTHORIZED_SIMULATION','protocol':'SIM-'+key,'xml_path':'/documents/'+key+'/xml'}
     xml='<?xml version="1.0"?><Transferencia ambiente="SIMULACAO" validadeFiscal="NENHUMA"><Documento>'+escape(key)+'</Documento><Entrega>'+escape(payload['delivery_id'])+'</Entrega><Origem>'+escape(payload['origin_id'])+'</Origem><Destino>'+escape(payload['destination_id'])+'</Destino><Itens>'+''.join('<Item produto="'+escape(i['product_id'])+'" quantidade="'+str(i['quantity'])+'" />' for i in payload['items'])+'</Itens></Transferencia>'
    c.execute('INSERT INTO documents VALUES(?,?,?,?)',(key,canonical,json.dumps(response),xml))
   if mode=='resposta-perdida':
    self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
   self.send(200,response)
  except (ValueError,KeyError,TypeError): self.send(400,{'message':'Payload inválido'})
ThreadingHTTPServer(('0.0.0.0',8090),Handler).serve_forever()
