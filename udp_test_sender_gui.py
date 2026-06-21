import tkinter as tk
from tkinter import ttk, messagebox
import socket
import json
import time

class ScrollableFrame(tk.Frame):
    def __init__(self, container, *args, **kwargs):
        super().__init__(container, *args, **kwargs)
        self.canvas = tk.Canvas(self, highlightthickness=0)
        self.scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.scrollable_frame = tk.Frame(self.canvas)

        self.scrollable_frame.bind(
            "<Configure>",
            lambda e: self.canvas.configure(
                scrollregion=self.canvas.bbox("all")
            )
        )

        self.canvas_window = self.canvas.create_window((0, 0), window=self.scrollable_frame, anchor="nw")
        self.canvas.bind('<Configure>', lambda event: self.canvas.itemconfig(self.canvas_window, width=event.width))
        self.canvas.configure(yscrollcommand=self.scrollbar.set)
        
        # Add mouse wheel support
        self.canvas.bind_all("<MouseWheel>", self._on_mousewheel)

        self.canvas.pack(side="left", fill="both", expand=True)
        self.scrollbar.pack(side="right", fill="y")
        
    def _on_mousewheel(self, event):
        self.canvas.yview_scroll(int(-1*(event.delta/120)), "units")

class UdpSenderGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("CarrotNavi Custom JSON Sender")
        self.root.geometry("750x850")
        
        # IP and Port Frame
        conn_frame = tk.LabelFrame(root, text="Connection", padx=10, pady=10)
        conn_frame.pack(fill="x", padx=10, pady=5)
        
        tk.Label(conn_frame, text="IP:").grid(row=0, column=0, sticky="w")
        self.ip_entry = tk.Entry(conn_frame, width=15)
        self.ip_entry.insert(0, "255.255.255.255")
        self.ip_entry.grid(row=0, column=1, padx=5)
        
        tk.Label(conn_frame, text="Port:").grid(row=0, column=2, sticky="w")
        self.port_entry = tk.Entry(conn_frame, width=8)
        self.port_entry.insert(0, "7706")
        self.port_entry.grid(row=0, column=3, padx=5)
        
        # Notebook for categorized fields
        self.notebook = ttk.Notebook(root)
        self.notebook.pack(fill="both", expand=True, padx=10, pady=5)
        
        self.fields = {}
        
        # Tabs
        self.tab_meta = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_meta, text="메타데이터 & 기본")
        
        self.tab_sdi = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_sdi, text="안전운행 (SDI)")
        
        self.tab_tbt = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_tbt, text="경로안내 (TBT)")
        
        self.tab_dest = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_dest, text="목적지 (Dest)")
        
        self.tab_loc = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_loc, text="위치 (Location)")
        
        # Populate Meta Tab
        self.add_field(self.tab_meta.scrollable_frame, "carrotIndex", "패킷 번호 (자동증가)", "0")
        self.add_field(self.tab_meta.scrollable_frame, "navitype", "내비게이션 소스", "tmap")
        self.add_field(self.tab_meta.scrollable_frame, "carrotCmd", "오픈파일럿 제어 명령", "")
        self.add_field(self.tab_meta.scrollable_frame, "carrotArg", "오픈파일럿 제어 인자", "")
        
        # Populate SDI Tab
        self.add_field(self.tab_sdi.scrollable_frame, "nRoadLimitSpeed", "현재 도로 제한속도", "100")
        self.add_field(self.tab_sdi.scrollable_frame, "roadcate", "도로종별 (0:고속,1:도시고속,2+:일반)", "0")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiType", "1차 이벤트 타입 (1:고정, 7:이동)", "1")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiSpeedLimit", "1차 이벤트 제한속도", "100")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiDist", "1차 이벤트 남은 거리", "500")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiSection", "구간단속 여부 (1:예, 0:아니오)", "1")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiBlockType", "구간단속 상태 (1:시작, 2:진행, 3:종료)", "2")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiBlockSpeed", "구간단속 제한 속도", "100")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiBlockAverageSpeed", "현재 평균속도", "95")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiBlockDist", "구간단속 남은 거리", "2000")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiPlusType", "2차 이벤트 타입 (연속 이벤트)", "0")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiPlusSpeedLimit", "2차 이벤트 제한속도", "0")
        self.add_field(self.tab_sdi.scrollable_frame, "nSdiPlusDist", "2차 이벤트 남은 거리", "0")

        # Populate TBT Tab
        self.add_field(self.tab_tbt.scrollable_frame, "nTBTTurnType", "회전 동작 종류 (12:좌, 13:우)", "0")
        self.add_field(self.tab_tbt.scrollable_frame, "szTBTMainText", "회전 안내 텍스트", "")
        self.add_field(self.tab_tbt.scrollable_frame, "nTBTDist", "회전 지점 남은 거리", "0")
        self.add_field(self.tab_tbt.scrollable_frame, "szNearDirName", "1차 진출 지명", "")
        self.add_field(self.tab_tbt.scrollable_frame, "szFarDirName", "2차 진출 지명", "")
        self.add_field(self.tab_tbt.scrollable_frame, "nTBTNextRoadWidth", "진입 도로 너비", "0")
        self.add_field(self.tab_tbt.scrollable_frame, "nTBTTurnTypeNext", "다음다음 회전 동작 종류", "0")
        self.add_field(self.tab_tbt.scrollable_frame, "nTBTDistNext", "다음다음 회전 지점 거리", "0")

        # Populate Dest Tab
        self.add_field(self.tab_dest.scrollable_frame, "szGoalName", "목적지 명칭", "집")
        self.add_field(self.tab_dest.scrollable_frame, "goalPosX", "목적지 경도", "127.1234")
        self.add_field(self.tab_dest.scrollable_frame, "goalPosY", "목적지 위도", "37.1234")
        self.add_field(self.tab_dest.scrollable_frame, "nGoPosDist", "목적지 남은 거리(m)", "15000")
        self.add_field(self.tab_dest.scrollable_frame, "nGoPosTime", "목적지 남은 시간(초)", "1800")
        
        # Populate Loc Tab
        self.add_field(self.tab_loc.scrollable_frame, "nPosSpeed", "차량 현재 속도 (TMap)", "80")
        self.add_field(self.tab_loc.scrollable_frame, "gps_speed", "차량 현재 속도 (GPS)", "80.5")
        self.add_field(self.tab_loc.scrollable_frame, "szPosRoadName", "현재 주행 중인 도로 이름", "경부고속도로")
        self.add_field(self.tab_loc.scrollable_frame, "vpPosPointLat", "맵매칭 위도", "37.5665")
        self.add_field(self.tab_loc.scrollable_frame, "vpPosPointLon", "맵매칭 경도", "126.9780")
        self.add_field(self.tab_loc.scrollable_frame, "nPosAngle", "방위각", "90")
        self.add_field(self.tab_loc.scrollable_frame, "accuracy", "GPS 오차", "5")
        
        # Free JSON input area
        custom_frame = tk.LabelFrame(root, text="Custom JSON / Preview", padx=10, pady=10)
        custom_frame.pack(fill="x", padx=10, pady=5)
        
        self.json_text = tk.Text(custom_frame, height=10)
        self.json_text.pack(fill="both", expand=True)
        
        # Button Frame
        btn_frame = tk.Frame(root)
        btn_frame.pack(fill="x", padx=10, pady=10)
        
        tk.Button(btn_frame, text="입력값으로 JSON 생성", command=self.generate_json, bg="#4CAF50", fg="white", font=("", 10, "bold")).pack(side="left", padx=5)
        tk.Button(btn_frame, text="UDP 패킷 전송 (Send)", command=self.send_udp, bg="#2196F3", fg="white", font=("", 12, "bold"), height=2, width=20).pack(side="right", padx=5)
        
        self.generate_json()

    def add_field(self, parent, key_name, label_desc, default_val):
        frame = tk.Frame(parent)
        frame.pack(fill="x", pady=4, padx=5)
        
        tk.Label(frame, text=key_name, width=25, anchor="w", font=("Consolas", 10, "bold")).pack(side="left")
        tk.Label(frame, text=label_desc, width=35, anchor="w", fg="#666666").pack(side="left")
        
        entry = tk.Entry(frame, width=15)
        entry.insert(0, default_val)
        entry.pack(side="left")
        self.fields[key_name] = entry
        
    def generate_json(self):
        data = {}
        data["epochTime"] = int(time.time() * 1000)
        for key, entry in self.fields.items():
            val = entry.get().strip()
            if val:
                try:
                    # try to parse as float or int
                    if "." in val:
                        data[key] = float(val)
                    else:
                        data[key] = int(val)
                except ValueError:
                    data[key] = val
                    
        formatted = json.dumps(data, ensure_ascii=False, indent=2)
        self.json_text.delete("1.0", tk.END)
        self.json_text.insert(tk.END, formatted)
        
    def send_udp(self):
        ip = self.ip_entry.get().strip()
        port = int(self.port_entry.get().strip())
        json_str = self.json_text.get("1.0", tk.END).strip()
        
        try:
            # validate json
            json.loads(json_str)
            
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.sendto(json_str.encode('utf-8'), (ip, port))
            sock.close()
            
            # Increment carrotIndex
            curr_idx = int(self.fields["carrotIndex"].get() or 0)
            self.fields["carrotIndex"].delete(0, tk.END)
            self.fields["carrotIndex"].insert(0, str(curr_idx + 1))
            self.generate_json()
            
            self.root.title("CarrotNavi Custom JSON Sender (전송 완료!)")
            self.root.after(1500, lambda: self.root.title("CarrotNavi Custom JSON Sender"))
            
        except Exception as e:
            messagebox.showerror("전송 에러", f"에러가 발생했습니다:\n{str(e)}")

if __name__ == "__main__":
    root = tk.Tk()
    app = UdpSenderGUI(root)
    root.mainloop()
