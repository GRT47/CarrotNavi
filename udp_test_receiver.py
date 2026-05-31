import socket
import json
import threading
import tkinter as tk
from tkinter import ttk
from datetime import datetime

# Openpilot (Carrot) UDP Listener Configuration
UDP_IP = "0.0.0.0"
UDP_PORT = 7706

def get_sdi_type_name(sdi_type):
    mapping = {
        0: "이벤트 없음",
        1: "고정식 과속카메라",
        2: "신호/과속카메라",
        3: "이동식 과속카메라",
        4: "구간단속(시작)",
        5: "구간단속(진행)",
        6: "구간단속(종료)",
        7: "이동식 단속구역",
        22: "과속방지턱",
        33: "어린이보호구역",
    }
    return mapping.get(sdi_type, f"기타/알수없음({sdi_type})")

class UdpReceiverGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("Openpilot (Carrot) UDP Receiver Test")
        self.root.geometry("600x700")
        self.root.configure(bg="#1e1e1e")
        
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background="#1e1e1e")
        style.configure("TLabel", background="#1e1e1e", foreground="#ffffff", font=("Helvetica", 12))
        style.configure("Header.TLabel", font=("Helvetica", 16, "bold"), foreground="#4CAF50")
        style.configure("Value.TLabel", font=("Helvetica", 24, "bold"), foreground="#2196F3")
        style.configure("Warning.TLabel", font=("Helvetica", 20, "bold"), foreground="#FF5722")
        
        main_frame = ttk.Frame(self.root, padding="20 20 20 20")
        main_frame.pack(fill=tk.BOTH, expand=True)

        # Status Label
        self.status_label = ttk.Label(main_frame, text=f"대기 중... (포트: {UDP_PORT})", style="Header.TLabel")
        self.status_label.pack(pady=(0, 20))

        # Dashboard Frame
        dash_frame = ttk.Frame(main_frame)
        dash_frame.pack(fill=tk.X, pady=10)

        # Navi Type
        ttk.Label(dash_frame, text="내비 타입:").grid(row=0, column=0, sticky="w", pady=5, padx=10)
        self.lbl_navitype = ttk.Label(dash_frame, text="-", style="Value.TLabel")
        self.lbl_navitype.grid(row=0, column=1, sticky="w", pady=5)

        # Road Limit Speed
        ttk.Label(dash_frame, text="도로 제한속도:").grid(row=1, column=0, sticky="w", pady=5, padx=10)
        self.lbl_road_limit = ttk.Label(dash_frame, text="0 km/h", style="Value.TLabel")
        self.lbl_road_limit.grid(row=1, column=1, sticky="w", pady=5)

        # Event Frame
        event_frame = tk.LabelFrame(main_frame, text="전방 이벤트 정보", bg="#1e1e1e", fg="#4CAF50", font=("Helvetica", 12, "bold"))
        event_frame.pack(fill=tk.X, pady=10, ipadx=10, ipady=10)

        ttk.Label(event_frame, text="종류:").grid(row=0, column=0, sticky="w", pady=5, padx=10)
        self.lbl_event_type = ttk.Label(event_frame, text="이벤트 없음", style="Warning.TLabel")
        self.lbl_event_type.grid(row=0, column=1, sticky="w", pady=5)

        ttk.Label(event_frame, text="제한속도:").grid(row=1, column=0, sticky="w", pady=5, padx=10)
        self.lbl_event_speed = ttk.Label(event_frame, text="-", style="Value.TLabel")
        self.lbl_event_speed.grid(row=1, column=1, sticky="w", pady=5)

        ttk.Label(event_frame, text="남은거리:").grid(row=2, column=0, sticky="w", pady=5, padx=10)
        self.lbl_event_dist = ttk.Label(event_frame, text="-", style="Value.TLabel")
        self.lbl_event_dist.grid(row=2, column=1, sticky="w", pady=5)

        # Block Speed Zone Frame
        block_frame = tk.LabelFrame(main_frame, text="구간 단속 정보", bg="#1e1e1e", fg="#4CAF50", font=("Helvetica", 12, "bold"))
        block_frame.pack(fill=tk.X, pady=10, ipadx=10, ipady=10)

        self.lbl_block_info = ttk.Label(block_frame, text="구간 단속 아님", style="TLabel")
        self.lbl_block_info.pack(anchor="w", padx=10, pady=5)

        # Log Text Box
        log_frame = ttk.Frame(main_frame)
        log_frame.pack(fill=tk.BOTH, expand=True, pady=(20, 0))
        
        ttk.Label(log_frame, text="수신 로그 (최신 패킷):", font=("Helvetica", 10, "bold")).pack(anchor="w")
        
        # Log Control Buttons
        btn_frame = ttk.Frame(log_frame)
        btn_frame.pack(fill=tk.X, pady=(0, 5))
        
        self.is_paused = False
        self.btn_pause = ttk.Button(btn_frame, text="⏸️ 로그 정지 (복사용)", command=self.toggle_pause)
        self.btn_pause.pack(side=tk.LEFT, padx=5)
        
        self.btn_clear = ttk.Button(btn_frame, text="🗑️ 로그 지우기", command=lambda: self.log_text.delete('1.0', tk.END))
        self.btn_clear.pack(side=tk.LEFT, padx=5)
        
        self.log_text = tk.Text(log_frame, height=10, bg="#2d2d2d", fg="#a9b7c6", font=("Consolas", 10))
        self.log_text.pack(fill=tk.BOTH, expand=True)

        # UDP Thread
        self.running = True
        self.thread = threading.Thread(target=self.udp_listener_thread)
        self.thread.daemon = True
        self.thread.start()

    def toggle_pause(self):
        self.is_paused = not self.is_paused
        if self.is_paused:
            self.btn_pause.config(text="▶️ 로그 재개")
            self.status_label.config(text="수신 로그 일시정지됨", foreground="#FF9800")
        else:
            self.btn_pause.config(text="⏸️ 로그 정지 (복사용)")
            self.status_label.config(text=f"정상 수신 중 (포트: {UDP_PORT})", foreground="#4CAF50")

    def update_ui(self, data):
        navitype = data.get("navitype", "없음")
        self.lbl_navitype.config(text=navitype)

        road_limit = data.get("nRoadLimitSpeed", 0)
        self.lbl_road_limit.config(text=f"{road_limit} km/h")

        sdi_type = data.get("nSdiType", 0)
        self.lbl_event_type.config(text=get_sdi_type_name(sdi_type))

        if sdi_type > 0:
            self.lbl_event_speed.config(text=f"{data.get('nSdiSpeedLimit', 0)} km/h")
            self.lbl_event_dist.config(text=f"{data.get('nSdiDist', 0)} m")
            self.lbl_event_type.config(foreground="#FF5722") # Red-ish for event
        else:
            self.lbl_event_speed.config(text="-")
            self.lbl_event_dist.config(text="-")
            self.lbl_event_type.config(foreground="#ffffff") # White for no event

        block_type = data.get("nSdiBlockType", 0)
        if block_type > 0:
            block_state = "진입" if block_type == 1 else ("진행중" if block_type == 2 else "종료")
            block_speed = data.get("nSdiBlockSpeed", 0)
            block_dist = data.get("nSdiBlockDist", 0)
            self.lbl_block_info.config(
                text=f"상태: {block_state}  |  제한속도: {block_speed} km/h  |  남은거리: {block_dist} m",
                foreground="#FFC107" # Yellow
            )
        else:
            self.lbl_block_info.config(text="구간 단속 아님", foreground="#ffffff")

        # Handle overlapping secondary event (nSdiPlus)
        plus_type = data.get("nSdiPlusType", 0)
        if plus_type > 0:
            plus_speed = data.get("nSdiPlusSpeedLimit", 0)
            plus_dist = data.get("nSdiPlusDist", 0)
            plus_name = get_sdi_type_name(plus_type)
            
            # Append +[Second Event] to the UI
            current_type_text = self.lbl_event_type.cget("text")
            self.lbl_event_type.config(text=f"{current_type_text} & {plus_name}")
            
            current_speed_text = self.lbl_event_speed.cget("text")
            self.lbl_event_speed.config(text=f"{current_speed_text} / {plus_speed}km/h")
            
            current_dist_text = self.lbl_event_dist.cget("text")
            self.lbl_event_dist.config(text=f"{current_dist_text} / {plus_dist}m")
            self.lbl_event_type.config(foreground="#FF5722")
            
        # Update log only if not paused
        if not self.is_paused:
            timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
            log_entry = f"[{timestamp}] {json.dumps(data, ensure_ascii=False)}\n"
            self.log_text.insert(tk.END, log_entry)
            self.log_text.see(tk.END)
            
            # Keep only last 50 lines to prevent memory bloat
            lines = int(self.log_text.index('end-1c').split('.')[0])
            if lines > 50:
                self.log_text.delete('1.0', f'{lines - 50}.0')

    def udp_listener_thread(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind((UDP_IP, UDP_PORT))
            self.root.after(0, lambda: self.status_label.config(text=f"정상 수신 중 (포트: {UDP_PORT})", foreground="#4CAF50"))
        except Exception as e:
            self.root.after(0, lambda e=e: self.status_label.config(text=f"포트 바인딩 실패: {e}", foreground="#F44336"))
            return

        while self.running:
            try:
                data, addr = sock.recvfrom(4096)
                payload = data.decode('utf-8')
                json_data = json.loads(payload)
                # Use after to safely update GUI from thread
                self.root.after(0, self.update_ui, json_data)
            except json.JSONDecodeError:
                pass
            except Exception as e:
                print("UDP Error:", e)

    def on_closing(self):
        self.running = False
        self.root.destroy()

if __name__ == "__main__":
    root = tk.Tk()
    app = UdpReceiverGUI(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    root.mainloop()
