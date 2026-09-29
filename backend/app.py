import os
import re
import sys
import socket
import asyncio
import datetime
import random
import urllib.parse
import ipaddress
from typing import Optional, List, Dict, Tuple
from fastapi import FastAPI, Query, HTTPException, WebSocket, WebSocketDisconnect, Response, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse
from ddgs import DDGS

if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

try:
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization
except ImportError:
    pass

app = FastAPI(title="SketchRef AI Render Studio", version="16.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

REFERENCE_CACHE: Dict[str, dict] = {}
INDEX_HTML_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "index.html"))

FAVICON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="%233b82f6" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m9.06 11.9 8.07-8.06a2.85 2.85 0 1 1 4.03 4.03l-8.06 8.08"/><path d="M7.07 14.94c-1.66 0-3 1.35-3 3.02 0 1.33-2.5 1.52-2 2.02 1.08 1.1 2.49 2.02 4 2.02 2.2 0 4-1.8 4-4.04a3.01 3.01 0 0 0-3-3.02z"/></svg>"""


def get_local_lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.2)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"


def ensure_ssl_certificates(lan_ip: str) -> Tuple[str, str]:
    cert_file = os.path.join(os.path.dirname(__file__), "cert.pem")
    key_file = os.path.join(os.path.dirname(__file__), "key.pem")

    if os.path.exists(cert_file) and os.path.exists(key_file):
        return cert_file, key_file

    print(f"[SSL] Generating local SSL certificates for LAN IP: {lan_ip}...")
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, lan_ip),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "SketchRef AI"),
    ])

    san_list = [
        x509.DNSName("localhost"),
        x509.IPAddress(ipaddress.IPv4Address("127.0.0.1")),
    ]
    try:
        san_list.append(x509.IPAddress(ipaddress.IPv4Address(lan_ip)))
    except Exception:
        pass

    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime.now(datetime.timezone.utc))
        .not_valid_after(datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=365))
        .add_extension(x509.SubjectAlternativeName(san_list), critical=False)
        .sign(key, hashes.SHA256())
    )

    with open(key_file, "wb") as f:
        f.write(key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        ))
    with open(cert_file, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))

    return cert_file, key_file


# -------------------------------------------------------------
# Bi-directional WebSocket Studio Hub
# -------------------------------------------------------------
class StudioSyncHub:
    def __init__(self):
        self.active_rooms: Dict[str, List[WebSocket]] = {}

    async def connect(self, websocket: WebSocket, session_id: str):
        await websocket.accept()
        if session_id not in self.active_rooms:
            self.active_rooms[session_id] = []
        self.active_rooms[session_id].append(websocket)

    def disconnect(self, websocket: WebSocket, session_id: str):
        if session_id in self.active_rooms:
            if websocket in self.active_rooms[session_id]:
                self.active_rooms[session_id].remove(websocket)
            if not self.active_rooms[session_id]:
                del self.active_rooms[session_id]

    async def broadcast(self, message: dict, session_id: str, sender: WebSocket):
        if session_id in self.active_rooms:
            for connection in self.active_rooms[session_id]:
                if connection != sender:
                    try:
                        await connection.send_json(message)
                    except Exception:
                        pass


sync_hub = StudioSyncHub()


@app.websocket("/ws/session/{session_id}")
async def websocket_session_endpoint(websocket: WebSocket, session_id: str):
    await sync_hub.connect(websocket, session_id)
    try:
        while True:
            data = await websocket.receive_json()
            await sync_hub.broadcast(data, session_id, websocket)
    except (WebSocketDisconnect, ConnectionResetError):
        sync_hub.disconnect(websocket, session_id)
    except Exception:
        sync_hub.disconnect(websocket, session_id)


# -------------------------------------------------------------
# REST Endpoints
# -------------------------------------------------------------
@app.get("/", include_in_schema=False)
async def serve_homepage():
    if os.path.exists(INDEX_HTML_PATH):
        return FileResponse(INDEX_HTML_PATH)
    return {"error": f"index.html not found at: {INDEX_HTML_PATH}"}


@app.get("/favicon.ico", include_in_schema=False)
async def get_favicon():
    return Response(content=FAVICON_SVG, media_type="image/svg+xml")


@app.get("/api/lan-ip")
async def get_lan_ip():
    return {"ip": get_local_lan_ip(), "port": int(os.environ.get("PORT", 8000))}


def parse_artist_query(query: str) -> Tuple[str, str, str, List[str]]:
    q = query.lower()

    angle = "Eye-Level (Natural)"
    if any(k in q for k in ["low", "worm", "bottom", "heroic", "upward"]):
        angle = "Worm's-Eye (Low-Angle)"
    elif any(k in q for k in ["high", "bird", "top", "overhead", "down"]):
        angle = "Bird's-Eye (High-Angle)"
    elif any(k in q for k in ["foreshorten", "reach", "dynamic"]):
        angle = "Foreshortened Dynamic"
    elif any(k in q for k in ["profile", "side"]):
        angle = "Side Profile"

    lighting = "Natural Ambient"
    if any(k in q for k in ["rim", "halo", "backlight", "edge"]):
        lighting = "Dramatic Rim Light"
    elif any(k in q for k in ["chiaroscuro", "split", "caravaggio", "contrast", "shadow"]):
        lighting = "Chiaroscuro / High Contrast"
    elif any(k in q for k in ["soft", "window", "skylight", "diffused"]):
        lighting = "Soft North Skylight"
    elif any(k in q for k in ["direct", "harsh", "noon", "sun"]):
        lighting = "Hard Direct Sun"

    strip_words = {
        "dramatic", "rim", "light", "lighting", "low", "angle", "high", "view",
        "eye", "level", "chiaroscuro", "shadow", "contrast", "soft", "hard",
        "direct", "sun", "sunlight", "ambient", "heroic", "photo", "picture",
        "image", "reference", "references", "the", "a", "an", "with", "and", "in", "for"
    }
    raw_tokens = re.findall(r"[a-zA-Z0-9]+", q)
    subject_tokens = [w for w in raw_tokens if w not in strip_words]
    core_subject = " ".join(subject_tokens).strip() or query.strip()

    return core_subject, angle, lighting, subject_tokens


def search_web_images(query: str, angle: str, lighting: str, limit: int = 16) -> List[dict]:
    results = []
    try:
        with DDGS() as ddgs:
            raw_results = list(ddgs.images(query, region="wt-wt", safesearch="off", max_results=limit))
            for item in raw_results:
                full_img = item.get("image")
                thumb_img = item.get("thumbnail") or full_img
                if not full_img:
                    continue

                lower_url = full_img.lower()
                if any(bad in lower_url for bad in ["placeholder", "no-image", "no_image", "default_avatar", "logo-square"]):
                    continue

                raw_title = item.get("title") or query
                clean_title = re.sub(r"[\.\-\_\|].*$", "", raw_title).strip()[:40].title() or query.title()

                if any(spam in clean_title.lower() for spam in ["university", "department of", "admission", "fuuast"]):
                    continue

                ref_id = f"img_{abs(hash(full_img)) % 10000000}"
                ref_item = {
                    "id": ref_id,
                    "title": clean_title,
                    "category": "Visual Reference",
                    "angle": angle,
                    "lighting": lighting,
                    "imageUrl": full_img,
                    "thumbnailUrl": thumb_img,
                    "aspect": "portrait" if item.get("height", 1) >= item.get("width", 1) else "landscape",
                    "tags": [query],
                }
                results.append(ref_item)
                REFERENCE_CACHE[ref_id] = ref_item

    except Exception as exc:
        print(f"[Image Search Info]: {exc}")

    return results


@app.get("/api/search")
async def search_references(
    q: str = Query("dramatic rim light knight low angle", description="Natural query"),
    category: Optional[str] = Query(None, description="Optional category filter")
):
    core_subject, angle, lighting, keywords = parse_artist_query(q)
    results = search_web_images(q, angle, lighting, limit=16)
    if len(results) < 4 and core_subject:
        extra = search_web_images(f"{core_subject} reference drawing", angle, lighting, limit=12)
        results.extend(extra)

    return {
        "query": q,
        "parsed_query": {
            "subject": core_subject.upper(),
            "angle": angle,
            "lighting": lighting,
            "keywords": keywords or [core_subject],
        },
        "total": len(results),
        "results": results,
    }


@app.get("/api/pinterest")
async def search_pinterest(
    q: str = Query("character design sketch", description="Pinterest search query")
):
    clean_q = re.sub(r"site:[^\s]+", "", q).strip()
    pinterest_query = f"{clean_q} drawing art reference pinterest"
    raw_pins = search_web_images(pinterest_query, "Dynamic Study", "Studio Light", limit=24)

    if len(raw_pins) < 6:
        extra = search_web_images(f"{clean_q} sketch aesthetic study", "Dynamic Study", "Studio Light", limit=16)
        raw_pins.extend(extra)

    filtered_pins = []
    seen = set()
    for item in raw_pins:
        if item["imageUrl"] in seen:
            continue
        seen.add(item["imageUrl"])
        item["category"] = "Pinterest Pin"
        filtered_pins.append(item)

    return {
        "query": clean_q,
        "total": len(filtered_pins),
        "results": filtered_pins[:18],
    }


@app.get("/api/generate-ai")
async def generate_ai_reference(
    prompt: str = Query(..., description="Artist visual prompt"),
    style: str = Query("classical sketch", description="Style modifier")
):
    clean_prompt = prompt.strip()
    full_prompt = f"{clean_prompt}, {style}, highly detailed reference study for artists, sharp focus, 8k resolution"
    encoded_prompt = urllib.parse.quote(full_prompt)
    seed = random.randint(1000, 999999)

    ai_image_url = f"https://image.pollinations.ai/prompt/{encoded_prompt}?width=768&height=1024&nologo=true&seed={seed}"

    ref_id = f"ai_{seed}"
    ai_item = {
        "id": ref_id,
        "title": clean_prompt[:38].title(),
        "category": "AI Generated",
        "angle": "Concept Perspective",
        "lighting": style.title(),
        "imageUrl": ai_image_url,
        "thumbnailUrl": ai_image_url,
        "aspect": "portrait",
        "tags": [clean_prompt, "ai", "generated"],
    }

    REFERENCE_CACHE[ref_id] = ai_item
    return ai_item


@app.post("/api/stash")
async def register_stash_image(request: Request):
    data = await request.json()
    ref_id = data.get("id")
    if not ref_id:
        raise HTTPException(status_code=400, detail="Missing reference ID")

    REFERENCE_CACHE[ref_id] = data
    return {"status": "ok", "id": ref_id}


@app.get("/api/reference/{ref_id}")
async def get_reference_by_id(ref_id: str):
    if ref_id in REFERENCE_CACHE:
        return REFERENCE_CACHE[ref_id]
    raise HTTPException(status_code=404, detail="Reference not found")


# -------------------------------------------------------------
# Mobile Companion Web Receiver (Render WebSocket Native)
# -------------------------------------------------------------
@app.get("/mobile", response_class=HTMLResponse)
async def mobile_companion(id: str = Query("default")):
    html_content = f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
      <title>SketchRef Companion</title>
      <link rel="icon" href="data:image/svg+xml,{urllib.parse.quote(FAVICON_SVG)}">
      <script src="https://cdn.tailwindcss.com"></script>
      <script src="https://unpkg.com/lucide@latest"></script>
    </head>
    <body class="bg-black text-white h-screen w-screen overflow-hidden flex flex-col select-none">
      
      <!-- Top Bar -->
      <div class="absolute top-0 inset-x-0 z-30 flex items-center justify-between p-3.5 bg-gradient-to-b from-black/90 to-transparent">
        <div class="flex items-center space-x-2">
          <span id="conn-dot" class="w-2.5 h-2.5 rounded-full bg-amber-400 animate-pulse"></span>
          <span id="conn-label" class="text-xs font-bold tracking-wide text-slate-200">PAIRING...</span>
        </div>
        <div class="flex items-center space-x-2">
          <span id="wakelock-pill" class="text-[10px] font-semibold px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">AWAKE</span>
          <button id="torch-btn" onclick="togglePhoneTorch()" class="px-3.5 py-1.5 rounded-xl bg-slate-800/95 border border-slate-600 text-xs font-bold flex items-center space-x-1.5 text-amber-300 shadow-lg active:scale-95 transition">
            <i data-lucide="flashlight" class="w-4 h-4"></i>
            <span id="torch-text">Torch OFF</span>
          </button>
        </div>
      </div>

      <!-- Viewport Stage -->
      <div id="mobile-stage" class="relative flex-1 w-full h-full flex items-center justify-center overflow-hidden">
        <video id="phone-camera" autoplay playsinline muted class="absolute inset-0 w-full h-full object-cover"></video>
        <canvas id="stream-canvas" class="hidden"></canvas>
        
        <div id="overlay-wrapper" class="absolute z-20 pointer-events-none transition-transform duration-75 flex items-center justify-center" style="width: 75%; transform-origin: 0 0; transform: translate(0px, 0px) scale(1) rotate(0deg);">
          <img id="overlay-ref" src="" alt="Overlay" class="w-full h-auto max-h-[85vh] object-contain shadow-2xl" style="opacity: 0.5;" />
        </div>
      </div>

      <!-- Bottom Bar -->
      <div class="absolute bottom-0 inset-x-0 z-30 p-4 bg-gradient-to-t from-black/95 to-transparent flex items-center justify-between">
        <div class="flex items-center space-x-2 flex-1 mr-4">
          <i data-lucide="eye" class="w-4 h-4 text-blue-400"></i>
          <input type="range" id="mobile-opacity" min="0" max="1" step="0.01" value="0.5" 
            oninput="handleOpacityInput(this.value)" class="w-full accent-blue-500 h-2 bg-slate-700 rounded-lg">
        </div>
        <span id="timer-badge" class="text-[11px] text-emerald-400 font-mono font-semibold">1:1 Synced</span>
      </div>

      <script>
        let currentRefId = "{id}";
        let ws = null;
        let videoTrack = null;
        let isTorchOn = false;
        let wakeLock = null;

        async function requestWakeLock() {{
          try {{
            if ('wakeLock' in navigator) {{
              wakeLock = await navigator.wakeLock.request('screen');
              document.getElementById('wakelock-pill').classList.remove('hidden');
            }}
          }} catch(e) {{
            document.getElementById('wakelock-pill').classList.add('hidden');
          }}
        }}

        document.addEventListener('visibilitychange', () => {{
          if (wakeLock !== null && document.visibilityState === 'visible') {{
            requestWakeLock();
          }}
        }});

        function connectWebSocket() {{
          const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
          ws = new WebSocket(proto + '//' + window.location.host + '/ws/session/' + currentRefId);

          ws.onopen = () => {{
            document.getElementById('conn-dot').className = "w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse";
            document.getElementById('conn-label').innerText = "LIVE SYNCED";
            ws.send(JSON.stringify({{ action: 'PHONE_CONNECTED' }}));
          }};

          ws.onmessage = (event) => {{
            try {{
              const msg = JSON.parse(event.data);

              if (msg.action === 'SWAP_REFERENCE') {{
                currentRefId = msg.refId;
                document.getElementById('overlay-ref').src = msg.imageUrl;
              }}

              if (msg.action === 'SYNC_TRANSFORM') {{
                const wrapper = document.getElementById('overlay-wrapper');
                const stage = document.getElementById('mobile-stage');

                if (msg.matrix3d) {{
                  wrapper.style.transformOrigin = "0 0";
                  wrapper.style.transform = msg.matrix3d;
                }} else {{
                  const stageW = stage.clientWidth;
                  const stageH = stage.clientHeight;
                  const tx = (msg.xPct || 0) * stageW;
                  const ty = (msg.yPct || 0) * stageH;
                  const scale = msg.scale || 1;
                  const mirror = msg.mirrored ? -1 : 1;
                  const rot = msg.rotation || 0;
                  wrapper.style.transformOrigin = "center center";
                  wrapper.style.transform = `translate(${{tx}}px, ${{ty}}px) scale(${{scale * mirror}}, ${{scale}}) rotate(${{rot}}deg)`;
                }}

                if (msg.opacity !== undefined) {{
                  document.getElementById('overlay-ref').style.opacity = msg.opacity;
                  document.getElementById('mobile-opacity').value = msg.opacity;
                }}

                if (msg.blendMode) {{
                  document.getElementById('overlay-ref').style.mixBlendMode = msg.blendMode;
                }}

                if (msg.contour !== undefined) {{
                  document.getElementById('overlay-ref').style.filter = msg.contour ? 'grayscale(100%) contrast(300%) invert(100%)' : 'none';
                }}
              }}

              if (msg.action === 'TOGGLE_TORCH') {{
                setTorch(msg.enabled);
              }}

              if (msg.action === 'SET_OPACITY') {{
                document.getElementById('overlay-ref').style.opacity = msg.value;
                document.getElementById('mobile-opacity').value = msg.value;
              }}
            }} catch(e) {{}}
          }};

          ws.onclose = () => {{
            document.getElementById('conn-dot').className = "w-2.5 h-2.5 rounded-full bg-red-400";
            document.getElementById('conn-label').innerText = "RECONNECTING...";
            setTimeout(connectWebSocket, 1200);
          }};
        }}

        async function initCamera() {{
          try {{
            const stream = await navigator.mediaDevices.getUserMedia({{
              video: {{
                facingMode: {{ ideal: "environment" }},
                width: {{ ideal: 1280 }},
                height: {{ ideal: 720 }}
              }},
              audio: false
            }});

            const video = document.getElementById('phone-camera');
            video.srcObject = stream;
            videoTrack = stream.getVideoTracks()[0];
            await video.play();

            if (ws && ws.readyState === WebSocket.OPEN) {{
              ws.send(JSON.stringify({{ action: 'CAMERA_READY' }}));
            }}

            startFrameStreamer(video);
          }} catch(err) {{
            console.error("Camera Error:", err);
          }}
        }}

        function startFrameStreamer(video) {{
          const canvas = document.getElementById('stream-canvas');
          const ctx = canvas.getContext('2d');

          setInterval(() => {{
            if (ws && ws.readyState === WebSocket.OPEN && video.readyState >= 2 && video.videoWidth > 0) {{
              if (ws.bufferedAmount > 0) return;

              const vw = video.videoWidth;
              const vh = video.videoHeight;
              const targetMax = 640;
              let tw, th;
              if (vw > vh) {{
                tw = targetMax;
                th = Math.round((vh / vw) * targetMax);
              }} else {{
                th = targetMax;
                tw = Math.round((vw / vh) * targetMax);
              }}

              canvas.width = tw;
              canvas.height = th;
              ctx.drawImage(video, 0, 0, tw, th);

              const base64 = canvas.toDataURL('image/jpeg', 0.5);
              ws.send(JSON.stringify({{
                action: 'CAMERA_FRAME',
                frame: base64,
                aspectRatio: vw / vh
              }}));
            }}
          }}, 80);
        }}

        async function setTorch(enable) {{
          isTorchOn = Boolean(enable);
          const btnText = document.getElementById('torch-text');
          const btn = document.getElementById('torch-btn');

          btnText.innerText = isTorchOn ? "Torch ON" : "Torch OFF";
          if (isTorchOn) {{
            btn.classList.add('bg-amber-500/30', 'border-amber-400');
          }} else {{
            btn.classList.remove('bg-amber-500/30', 'border-amber-400');
          }}

          if (!videoTrack) return;

          try {{
            await videoTrack.applyConstraints({{
              advanced: [{{ torch: isTorchOn }}]
            }});
          }} catch(e) {{
            console.warn("Torch hardware error:", e);
          }}

          if (ws && ws.readyState === WebSocket.OPEN) {{
            ws.send(JSON.stringify({{ action: 'TORCH_STATUS', enabled: isTorchOn }}));
          }}
        }}

        function togglePhoneTorch() {{
          setTorch(!isTorchOn);
        }}

        function handleOpacityInput(val) {{
          document.getElementById('overlay-ref').style.opacity = val;
          if (ws && ws.readyState === WebSocket.OPEN) {{
            ws.send(JSON.stringify({{ action: 'SET_OPACITY', value: val }}));
          }}
        }}

        window.addEventListener('DOMContentLoaded', async () => {{
          lucide.createIcons();

          try {{
            const res = await fetch('/api/reference/' + currentRefId);
            if (res.ok) {{
              const data = await res.json();
              document.getElementById('overlay-ref').src = data.thumbnailUrl || data.imageUrl;
            }}
          }} catch(e) {{}}

          connectWebSocket();
          initCamera();
          requestWakeLock();
        }});
      </script>
    </body>
    </html>
    """
    return HTMLResponse(content=html_content)


# -------------------------------------------------------------
# Production Render vs Local Development Launcher
# -------------------------------------------------------------
if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 8000))
    is_render = os.environ.get("RENDER")

    if is_render:
        print(f"[Render] Starting production server on port {port}...")
        uvicorn.run("app:app", host="0.0.0.0", port=port)
    else:
        lan_ip = get_local_lan_ip()
        cert_path, key_path = ensure_ssl_certificates(lan_ip)
        print(f"\n=======================================================")
        print(f"SketchRef AI Local Server Running on HTTPS")
        print(f"  PC Studio:       https://localhost:{port}")
        print(f"  Mobile Mirror:   https://{lan_ip}:{port}")
        print(f"=======================================================\n")
        uvicorn.run(
            app,
            host="0.0.0.0",
            port=port,
            ssl_keyfile=key_path,
            ssl_certfile=cert_path
        )