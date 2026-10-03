"""Optional real Studio protocol acceptance; fixture worker exports placeholders, never Photoshop.

Set CYCLONE_MRZ_TEST_SOURCE to a Studio 7.2.1+ checkout. No owner settings, templates or jobs are touched.
"""
import json
import os
import subprocess
import time
from pathlib import Path

import pytest

from test_ports_phone import FakePhone, answered, bridge_for, PNG
from test_ports_traffic import Gateway
from cyclone_device_gateway.ports.id_generator import PORTS

NODE_HOST = r"""
const fs=require('fs'),path=require('path'),http=require('http');
const {createPlugin}=require(path.join(process.env.CYCLONE_MRZ_TEST_SOURCE,'app/local-server/id-generator/plugin.js'));
const root=process.env.CYCLONE_MRZ_TEST_ROOT,jobs=new Map();let plugin;
const png=Buffer.from(process.env.CYCLONE_MRZ_TEST_PNG,'base64');
const paths={root,incoming:path.join(root,'incoming'),done:path.join(root,'done'),failed:path.join(root,'failed'),output:path.join(root,'output'),workerOutput:path.join(root,'worker-output'),workerCurrentJob:path.join(root,'current-job'),heartbeat:path.join(root,'heartbeat.json')};
const server=http.createServer(async(req,res)=>{
 if(req.url==='/health'||req.url==='/api/health'){res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({ok:true,dryRun:true,studio:{product:'mrz-studio-local',version:'7.2.1'},worker:{online:true}}));}
 if(await plugin.handle(req,res))return;res.writeHead(404);res.end();
});
server.listen(0,'127.0.0.1',()=>{
 plugin=createPlugin({control:path.join(root,'control'),paths,apiPort:server.address().port,
 render:async()=>({photo:png,signature:png}),
 load:id=>jobs.has(id)?{job:jobs.get(id),stage:'done'}:null,
 resolve:(id,name)=>path.join(paths.output,id,name),
 publish:async job=>{const dir=path.join(paths.output,job.id);fs.mkdirSync(dir,{recursive:true});for(const name of ['front.png','back.png'])fs.writeFileSync(path.join(dir,name),png);jobs.set(job.id,{...job,status:'complete',output_front_png_path:'front.png',output_back_png_path:'back.png'});}});
 fs.writeFileSync(path.join(root,'ready.json'),JSON.stringify({port:server.address().port}));
});
"""


@pytest.mark.skipif(not os.environ.get("CYCLONE_MRZ_TEST_SOURCE"), reason="optional local Studio contract acceptance")
def test_real_studio_pairing_portrait_and_correlated_phone_outputs(tmp_path):
    import base64
    root = tmp_path / "studio"; root.mkdir()
    env = dict(os.environ, CYCLONE_MRZ_TEST_ROOT=str(root), CYCLONE_MRZ_TEST_PNG=base64.b64encode(PNG).decode())
    env.pop("CYCLONE_PLUGIN_SECRET", None)
    process = subprocess.Popen(["node", "-e", NODE_HOST], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    gateway = None
    bridge = None
    try:
        deadline = time.monotonic() + 15
        while not (root / "ready.json").exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(.05)
        assert (root / "ready.json").exists(), process.stderr.read() if process.poll() is not None else "host did not start"
        port = json.loads((root / "ready.json").read_text())["port"]
        gateway = Gateway(tmp_path / "hub")
        starter = gateway.hub.id_generator
        starter.configure(dict(starter.config(), apiBase=f"http://127.0.0.1:{port}"))
        result = starter.connect(list(PORTS))
        assert result["plugin"]["status"] == "active", result["plugin"]["checks"]
        phone = FakePhone(); bridge = bridge_for(gateway, phone)
        bridge.poll("phone-1")
        assert phone.polls[-1]["skills"][0]["name"] == "id-generator"
        run = "m1employee01"
        phone.emit(run, "file.out", {"assetId": "photo1", "name": "portrait.png"}, PNG)
        phone.queue[-1]["plugin"] = "id-generator"
        phone.emit(run, "x.id-generator.generate", {"requestId": "employee1", "photoId": "photo1",
            "employee": {"first_name": "Sam", "last_name": "Example", "birth_date": "1990-06-14", "height_cm": 188, "city_of_birth": "Custom Town"}})
        phone.queue[-1]["plugin"] = "id-generator"
        bridge.poll("phone-1"); gateway.hub.traffic.flush()
        item = phone.wait(run, "value.in")
        phone.queue[-1].update(plugin="id-generator", match={"requestId": "employee1"})
        bridge.poll("phone-1")
        value = answered(phone, item, 20)
        assert value and value.get("value", {}).get("status") == "complete", value
        for output in ("front", "back"):
            item = phone.wait(run, "file.in")
            phone.queue[-1].update(plugin="id-generator", match={"requestId": "employee1", "output": output})
            bridge.poll("phone-1")
            answer = answered(phone, item)
            assert answer and answer["state"] == "delivered", answer
        assert len(phone.files) == 2 and all(bytes(raw) == PNG for raw in phone.files.values())
        assert value["value"]["requestId"] == "employee1"
        assert "Sam" not in json.dumps(gateway.hub.store.activity(500))
    finally:
        if bridge: bridge.stop(); bridge.join()
        if gateway: gateway.stop()
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
