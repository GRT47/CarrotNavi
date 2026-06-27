from fastapi import FastAPI, HTTPException
from fastapi.responses import RedirectResponse
from pydantic import BaseModel
import os
import json

app = FastAPI()
DATA_FILE = "device_ips.json"

# Load existing data on startup
def load_data():
    if os.path.exists(DATA_FILE):
        with open(DATA_FILE, "r") as f:
            try:
                return json.load(f)
            except json.JSONDecodeError:
                return {}
    return {}

device_ips = load_data()

def save_data():
    with open(DATA_FILE, "w") as f:
        json.dump(device_ips, f)

class IpUpdateRequest(BaseModel):
    device_id: str
    local_ip: str
    port: int = 8080

@app.post("/api/update_ip")
async def update_ip(req: IpUpdateRequest):
    device_ips[req.device_id] = {
        "ip": req.local_ip,
        "port": req.port
    }
    save_data()
    return {"status": "success", "message": f"Updated IP for {req.device_id}"}

@app.get("/connect/{device_id}")
async def connect(device_id: str):
    if device_id not in device_ips:
        raise HTTPException(status_code=404, detail="Device not found")
    
    device_info = device_ips[device_id]
    target_url = f"http://{device_info['ip']}:{device_info['port']}/"
    return RedirectResponse(url=target_url, status_code=302)

@app.get("/")
async def root():
    return {"status": "ok", "message": "CarrotNavi IP Redirect Server is running."}
