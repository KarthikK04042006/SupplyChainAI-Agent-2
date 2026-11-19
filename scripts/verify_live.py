import urllib.request
import json
import os
import mimetypes

BASE = "http://localhost:8080"

def post_json(path, data, token=None):
    req = urllib.request.Request(
        f"{BASE}{path}",
        data=json.dumps(data).encode("utf-8"),
        headers={"Content-Type": "application/json"} | ({"Authorization": f"Bearer {token}"} if token else {})
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))

def get_json(path, token):
    req = urllib.request.Request(
        f"{BASE}{path}",
        headers={"Authorization": f"Bearer {token}"}
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))

def upload_file(path, file_path, token):
    boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    filename = os.path.basename(file_path)
    with open(file_path, "rb") as f:
        file_bytes = f.read()

    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: text/csv\r\n\r\n"
    ).encode("utf-8") + file_bytes + f"\r\n--{boundary}--\r\n".encode("utf-8")

    req = urllib.request.Request(
        f"{BASE}{path}",
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}"
        }
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))

print("1. Authenticating as admin...")
auth = post_json("/api/auth/login", {"username": "admin", "password": "admin"})
token = auth["token"]
print("   Auth OK. Role:", auth.get("role"))

print("2. Uploading sample data...")
prod_res = upload_file("/api/products/import", "sample-data/products.csv", token)
print("   Products imported:", prod_res)

inv_res = upload_file("/api/inventory/import", "sample-data/inventory_levels.csv", token)
print("   Inventory imported:", inv_res)

dem_res = upload_file("/api/demand/import", "sample-data/demand_history.csv", token)
print("   Demand history imported:", dem_res)

print("3. Running Inventory Risk Scan...")
scan_res = post_json("/api/inventory/scan", {}, token)
print("   Scan completed. Total evaluated:", len(scan_res.get("items", scan_res)))

print("4. Fetching Dashboard Summary...")
summary = get_json("/api/dashboard/summary", token)
print("   Summary KPIs:", summary)

print("5. Testing Forecast for Product 1...")
prods = get_json("/api/products", token)
p1 = prods["items"][0]
p1_id = p1["id"]
p1_sku = p1["sku"]
print(f"   Product 1: ID={p1_id}, SKU={p1_sku}, Name={p1['name']}")

fc = get_json(f"/api/products/{p1_id}/forecast", token)
print(f"   Selected Model: {fc.get('selectedModel')}")
print(f"   Reason: {fc.get('selectionReason')}")
print(f"   Forecast points count: {len(fc.get('forecast', []))}")
if fc.get('forecast'):
    print(f"   First 3 days prediction: {fc['forecast'][:3]}")

print("6. Testing Reorder Recommendation...")
reorder = get_json(f"/api/products/{p1_id}/reorder-recommendation", token)
print("   Safety Stock:", reorder.get("safetyStock"))
print("   Reorder Point:", reorder.get("reorderPoint"))
print("   Recommended Order Qty:", reorder.get("orderQuantity"))
print("   Decision:", reorder.get("decision"))

print("7. Testing What-If Simulation...")
sim_body = {
    "sku": p1_sku,
    "demandShockPercent": 25.0,
    "leadTimeShockDays": 3
}
sim = post_json("/api/simulate", sim_body, token)
print("   Base vs Simulated ROP:", sim.get("baseReorderPoint"), "->", sim.get("simulatedReorderPoint"))
print("   Base vs Simulated Qty:", sim.get("baseOrderQuantity"), "->", sim.get("simulatedOrderQuantity"))

print("\nALL VERIFICATIONS PASSED SUCCESSFULLY!")

