"""H02/H03 via HTTP real. Requer base limpa e cria pedido/movimenta estoque."""
import json
import os
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.environ.get("CORE_URL", "http://core:8080" if os.path.exists("/.dockerenv") else "http://localhost:18080")


def call(path, method="GET", data=None, token="gestor-local"):
    request = urllib.request.Request(
        ROOT + "/admin" + path,
        data=json.dumps(data).encode() if data is not None else None,
        method=method,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)


def inventory():
    response = call("/inventory")
    assert response["success"], response
    return {(r["location_id"], r["product_id"]): r["stock"] for r in response["data"]}


def receipt(water, coffee):
    return {"items": [{"product_id": "agua-500", "received": water},
                      {"product_id": "cafe-250", "received": coffee}]}


before = inventory()
for key, expected in [(('centro-horizonte', 'agua-500'), 100),
                      (('loja-aurora', 'agua-500'), 4),
                      (('centro-horizonte', 'cafe-250'), 50),
                      (('loja-aurora', 'cafe-250'), 3)]:
    assert before[key] == expected, "Requer base limpa; este roteiro altera os dados"

payload = {"destination": "loja-aurora", "items": [
    {"product_id": "agua-500", "requested": 6},
    {"product_id": "cafe-250", "requested": 2}]}
unauthorized = call("/requests", "POST", payload, "operador-local")
assert not unauthorized["success"] and unauthorized["status"] == 403, unauthorized
created = call("/requests", "POST", payload)
assert created["success"], created
request_id = created["data"]["id"]
path = "/requests/" + request_id
unauthorized = call(path + "/confirm", "POST", receipt(4, 0))
assert not unauthorized["success"] and unauthorized["status"] == 403, unauthorized
for water, coffee in [(-1, 0), (7, 0), (4, 3), (0, 0)]:
    result = call(path + "/confirm", "POST", receipt(water, coffee), "operador-local")
    assert not result["success"] and result["status"] == 400, result
    assert inventory() == before
    assert call(path)["data"]["status"] == "OPEN"
    assert call(path + "/document")["data"]["status"] == "NOT_REQUESTED"

barrier = threading.Barrier(2)


def concurrent_confirm():
    barrier.wait(timeout=10)
    return call(path + "/confirm", "POST", receipt(4, 0), "operador-local")


with ThreadPoolExecutor(max_workers=2) as pool:
    futures = [pool.submit(concurrent_confirm) for _ in range(2)]
    results = [future.result(timeout=50) for future in futures]
assert all(result["success"] for result in results), results
assert results[0] == results[1], results
items = {item["product_id"]: item for item in results[0]["data"]["items"]}
for product, requested, received, missing in [('agua-500', 6, 4, 2), ('cafe-250', 2, 0, 2)]:
    assert [items[product][key] for key in ['requested', 'received', 'missing']] == [requested, received, missing]
expected = dict(before)
expected[('centro-horizonte', 'agua-500')] = 96
expected[('loja-aurora', 'agua-500')] = 8
assert inventory() == expected
assert call(path)["data"] == results[0]["data"]
deadline = time.monotonic() + 45
while True:
    document = call(path + "/document")["data"]
    if document["status"] == "AUTHORIZED_SIMULATION":
        break
    assert document["status"] not in ("FAILED", "REJECTED_SIMULATION"), document
    assert time.monotonic() < deadline, document
    time.sleep(0.25)
assert document["status"] == "AUTHORIZED_SIMULATION", document
assert document["attempts"] == 1, document
retry = call(path + "/confirm", "POST", receipt(4, 0), "operador-local")
assert retry == results[0], retry
conflict = call(path + "/confirm", "POST", receipt(3, 0), "operador-local")
assert not conflict["success"] and conflict["status"] == 409, conflict
assert inventory() == expected
assert call(path + "/document")["data"] == document
with urllib.request.urlopen(urllib.request.Request(
        ROOT + "/admin" + path + "/document/xml",
        headers={"Authorization": "Bearer operador-local"}), timeout=30) as response:
    assert b"SIMULACAO" in response.read()
print(json.dumps({"result": "H02/H03 OK", "request_id": request_id,
                  "water": {"center": 96, "aurora": 8, "requested": 6, "received": 4, "missing": 2},
                  "coffee": {"requested": 2, "received": 0, "missing": 2},
                  "identical_concurrent_calls": 2, "divergent_retry_status": 409,
                  "document_status": document["status"], "document_attempts": document["attempts"]},
                 ensure_ascii=False, indent=2))
