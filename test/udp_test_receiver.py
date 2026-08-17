import socket
import json
import threading
import tkinter as tk
from tkinter import ttk
from datetime import datetime
import math

# Openpilot (Carrot) UDP Listener Configuration
UDP_IP = "0.0.0.0"
UDP_PORT = 7706

# TMap / Openpilot SDI (Safety Driving Info) Type Mapping (Section 2)
SDI_MAPPING = {
    0: "이벤트 없음 / 신호과속",
    1: "과속 (고정식)",
    2: "구간단속 시작",
    3: "구간단속 끝",
    4: "구간단속중",
    5: "꼬리물기단속카메라",
    6: "신호 단속",
    7: "과속 (이동식)",
    8: "고정식 과속위험 구간(박스형)",
    9: "버스전용차로구간",
    10: "가변 차로 단속",
    11: "갓길 감시 지점",
    12: "끼어들기 금지",
    13: "교통정보 수집지점",
    14: "방범용 CCTV",
    15: "과적차량 위험구간",
    16: "적재 불량 단속",
    17: "주차단속 지점",
    18: "일방통행도로",
    19: "철길 건널목",
    20: "어린이 보호구역(스쿨존 시작 구간)",
    21: "어린이 보호구역(스쿨존 끝 구간)",
    22: "과속방지턱",
    23: "LPG 충전소",
    24: "터널 구간",
    25: "휴게소",
    26: "톨게이트",
    27: "안개주의 지역",
    28: "유해물질 지역",
    29: "사고다발",
    30: "급커브지역 / 단순 안내",
    31: "급커브구간1",
    32: "급경사구간 / 단순 안내",
    33: "야생동물 교통사고 잦은 구간",
    34: "우측시야불량지점",
    35: "시야불량지점",
    36: "좌측시야불량지점",
    37: "신호위반다발구간",
    38: "과속운행다발구간",
    39: "교통혼잡지역",
    40: "방향별차로선택지점",
    41: "무단횡단사고다발지점",
    42: "갓길 사고 다발 지점",
    43: "과속 사발 다발 지점",
    44: "졸음 사고 다발 지점",
    45: "사고다발지점",
    46: "보행자 사고다발지점",
    47: "차량도난사고 상습발생지점",
    48: "낙석주의지역",
    49: "결빙주의지역 / 단순 안내",
    50: "병목지점",
    51: "합류 도로",
    52: "추락주의지역",
    53: "지하차도 구간",
    54: "주택밀집지역(교통진정지역)",
    55: "인터체인지",
    56: "분기점",
    57: "휴게소(LPG충전가능)",
    58: "교량",
    59: "제동장치사고다발지점",
    60: "중앙선침범사고다발지점",
    61: "통행위반사고다발지점",
    62: "목적지 건너편 안내",
    63: "졸음 쉼터 안내",
    64: "노후경유차단속",
    65: "터널내 차로변경단속",
    75: "단속 카메라 / 진출 안내(좌)",
    76: "단속 카메라 / 진출 안내(우)"
}

# TMap / Openpilot TBT (Turn-by-Turn) Type Mapping (Section 3)
TBT_MAPPING = {
    0: "직진 / 안내 없음",
    6: "오른쪽 분기점/갈림길 (fork right)",
    7: "왼쪽 분기점/갈림길 (fork left)",
    12: "좌회전 (turn left)",
    13: "우회전 (turn right)",
    14: "유턴 (uturn)",
    15: "P턴",
    16: "왼쪽 급좌회전 (sharp left)",
    17: "왼쪽 갈림길 (fork left)",
    18: "왼쪽 고가도로 진입",
    19: "오른쪽 급우회전 (sharp right)",
    20: "터널 진입",
    43: "오른쪽 갈림길 (fork right)",
    44: "왼쪽 갈림길 (fork left)",
    51: "안내지점 (straight)",
    52: "안내지점 (straight)",
    53: "안내지점 (straight)",
    54: "안내지점 (straight)",
    55: "안내지점 (straight)",
    73: "오른쪽 갈림길 (fork right)",
    74: "오른쪽 갈림길 (fork right)",
    75: "왼쪽 갈림길 (fork left)",
    76: "왼쪽 갈림길 (fork left)",
    101: "오른쪽 진출 (slight right)",
    102: "왼쪽 진출 (slight left)",
    104: "오른쪽 진출 (slight right)",
    105: "왼쪽 진출 (slight left)",
    111: "오른쪽 진출 (slight right)",
    112: "왼쪽 진출 (slight left)",
    114: "오른쪽 진출 (slight right)",
    115: "왼쪽 진출 (slight left)",
    117: "오른쪽 갈림길 (fork right)",
    118: "왼쪽 갈림길 (fork left)",
    123: "오른쪽 갈림길 (fork right)",
    124: "오른쪽 갈림길 (fork right)",
    131: "로터리 우측진출 (slight right)",
    132: "로터리 우측진출 (slight right)",
    133: "로터리 우회전 (rotary right)",
    134: "로터리 급우회전 (sharp right)",
    135: "로터리 급우회전 (sharp right)",
    136: "로터리 급좌회전 (sharp left)",
    137: "로터리 급좌회전 (sharp left)",
    138: "로터리 급좌회전 (sharp left)",
    139: "로터리 좌회전 (rotary left)",
    140: "로터리 좌측진출 (slight left)",
    141: "로터리 좌측진출 (slight left)",
    142: "로터리 직진 (rotary straight)",
    153: "톨게이트 (TG)",
    154: "톨게이트 (TG)",
    200: "출발지",
    201: "목적지 도착 (arrive)",
    249: "톨게이트 (TG)"
}

# Detailed key specifications from telemetry guide
FIELDS_SPEC = {
    "metadata": {
        "carrotIndex": ("Index", "패킷 시퀀스 번호 (0, 1, 2, ...)"),
        "epochTime": ("Epoch Time", "단말기 UTC 타임스탬프 (Unix Time)"),
        "timezone": ("Timezone", "단말기 타임존 설정값 (예: 'Asia/Seoul')"),
        "carrotCmd": ("Cmd", "오픈파일럿 제어 명령 (예: 'DETECT')"),
        "carrotArg": ("Arg", "제어 명령에 포함할 인자 값")
    },
    "sdi": {
        "nRoadLimitSpeed": ("Road Limit", "현재 주행 도로 제한속도 (km/h)"),
        "roadcate": ("Road Cate", "도로종별 분류 (0,1: 고속, 2+: 일반)"),
        "nSdiType": ("SDI Type", "1차 안전운전 이벤트 타입 코드"),
        "nSdiSpeedLimit": ("SDI Speed", "1차 이벤트 제한속도 (km/h)"),
        "nSdiDist": ("SDI Dist", "1차 이벤트까지 남은 거리 (m)"),
        "nSdiSection": ("SDI Section", "구간단속 여부 플래그 (1: 예, 0: 아니오)"),
        "nSdiBlockType": ("Block Type", "구간단속 구간 상태 (1: 시작, 2: 진행, 3: 종료)"),
        "nSdiBlockSpeed": ("Block Speed", "구간단속 제한 속도 (km/h)"),
        "nSdiBlockDist": ("Block Dist", "구간단속 종료까지 남은 거리 (m)"),
        "nSdiPlusType": ("SDI Plus Type", "2차 안전운전 이벤트 타입 코드"),
        "nSdiPlusSpeedLimit": ("SDI Plus Speed", "2차 이벤트 제한속도 (km/h)"),
        "nSdiPlusDist": ("SDI Plus Dist", "2차 이벤트까지 남은 거리 (m)"),
        "nSdiPlusBlockType": ("Plus Block Type", "2차 구간단속 구간 상태 (1: 시작, 2: 진행, 3: 종료)"),
        "nSdiPlusBlockSpeed": ("Plus Block Speed", "2차 구간단속 제한 속도 (km/h)"),
        "nSdiPlusBlockDist": ("Plus Block Dist", "2차 구간단속 종료까지 남은 거리 (m)")
    },
    "tbt": {
        "nTBTDist": ("TBT Dist", "다음 회전 지점까지 남은 거리 (m)"),
        "nTBTTurnType": ("Turn Type", "회전 동작 종류 코드"),
        "szTBTMainText": ("TBT Text", "다음 회전 동작 설명 문자열 (안내 문구)"),
        "szNearDirName": ("Near Dir", "1차 진출 방면 지명 정보"),
        "szFarDirName": ("Far Dir", "2차 진출 방면 지명 정보"),
        "nTBTNextRoadWidth": ("Road Width", "회전 후 진입할 도로 너비 (m)"),
        "nTBTDistNext": ("Next TBT Dist", "다음다음 회전 지점까지 거리 (m)"),
        "nTBTTurnTypeNext": ("Next Turn Type", "다음다음 회전 동작 종류 코드")
    },
    "destination": {
        "goalPosX": ("Goal X", "목적지 경도 좌표 (Longitude)"),
        "goalPosY": ("Goal Y", "목적지 위도 좌표 (Latitude)"),
        "szGoalName": ("Goal Name", "목적지 명칭"),
        "nGoPosDist": ("Goal Dist", "목적지까지 남은 총 경로 주행 거리 (m)"),
        "nGoPosTime": ("Goal Time", "목적지까지 남은 예상 주행 소요 시간 (초)")
    },
    "location": {
        "vpPosPointLat": ("Map Lat", "맵매칭 위도 (Latitude)"),
        "vpPosPointLon": ("Map Lon", "맵매칭 경도 (Longitude)"),
        "nPosAngle": ("Map Angle", "맵매칭 기준 차량 진행 방위각 (0~360도)"),
        "nPosSpeed": ("Map Speed", "맵매칭 차량 현재 속도 (km/h)"),
        "szPosRoadName": ("Road Name", "현재 주행 중인 도로 이름"),
        "latitude": ("Raw Lat", "스마트폰 Raw GPS 위도"),
        "longitude": ("Raw Lon", "스마트폰 Raw GPS 경도"),
        "heading": ("Raw Heading", "스마트폰 Raw GPS 방위각"),
        "accuracy": ("GPS Accuracy", "스마트폰 GPS 정밀도 오차 범위 (m)"),
        "gps_speed": ("GPS Speed", "스마트폰 GPS 계측 속도 (km/h)")
    }
}

# Value Formatters
def format_epoch_time(epoch):
    if not epoch:
        return "-"
    try:
        if epoch > 1e11:
            epoch = epoch / 1000.0
        dt = datetime.fromtimestamp(epoch)
        return f"{epoch} ({dt.strftime('%Y-%m-%d %H:%M:%S')})"
    except Exception:
        return str(epoch)

def format_roadcate(roadcate_val):
    if roadcate_val is None:
        return "-"
    try:
        val = int(roadcate_val)
        if val == 0:
            return f"{val} (고속국도)"
        elif val == 1:
            return f"{val} (도시고속화도로)"
        elif val >= 2:
            return f"{val} (일반도로)"
        return str(val)
    except Exception:
        return str(roadcate_val)

def format_block_type(block_type):
    if block_type is None:
        return "-"
    try:
        val = int(block_type)
        mapping = {
            0: "0 (구간단속 아님)",
            1: "1 (구간단속 시작지점)",
            2: "2 (구간 내 주행중)",
            3: "3 (구간단속 종료지점)"
        }
        return mapping.get(val, str(val))
    except Exception:
        return str(block_type)

def format_time_duration(seconds):
    if seconds is None:
        return "-"
    try:
        val = int(seconds)
        if val == 0:
            return "0초"
        hours = val // 3600
        minutes = (val % 3600) // 60
        secs = val % 60
        res = []
        if hours > 0:
            res.append(f"{hours}시간")
        if minutes > 0:
            res.append(f"{minutes}분")
        if secs > 0 or not res:
            res.append(f"{secs}초")
        return f"{val}초 ({' '.join(res)})"
    except Exception:
        return str(seconds)

def get_sdi_type_name(sdi_type):
    if sdi_type is None:
        return "정보 없음"
    return SDI_MAPPING.get(int(sdi_type), f"기타/알수없음 ({sdi_type})")

def get_tbt_turn_type_name(turn_type):
    if turn_type is None or turn_type < 0:
        return "정보 없음"
    return TBT_MAPPING.get(int(turn_type), f"기타/알수없음 ({turn_type})")

def draw_speed_limit(canvas, speed):
    canvas.delete("all")
    # Draw a speed limit sign: white circle with thick red border, black text inside
    canvas.create_oval(5, 5, 75, 75, outline="#FF3333", width=6, fill="#FFFFFF")
    try:
        val = int(speed)
        if 0 < val < 200:
            text = str(val)
        else:
            text = "-"
    except Exception:
        text = "-"
    canvas.create_text(40, 40, text=text, fill="#000000", font=("Helvetica", 22, "bold"))


class ScrollableFrame(tk.Frame):
    def __init__(self, container, *args, **kwargs):
        super().__init__(container, *args, **kwargs)
        self.canvas = tk.Canvas(self, bg="#1e1e1e", highlightthickness=0)
        self.scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.scrollable_frame = tk.Frame(self.canvas, bg="#1e1e1e")

        self.scrollable_frame.bind(
            "<Configure>",
            lambda e: self.canvas.configure(
                scrollregion=self.canvas.bbox("all")
            )
        )

        self.canvas_window = self.canvas.create_window((0, 0), window=self.scrollable_frame, anchor="nw")
        
        # Ensure scrollable frame expands horizontally
        self.canvas.bind('<Configure>', lambda event: self.canvas.itemconfig(self.canvas_window, width=event.width))

        self.canvas.configure(yscrollcommand=self.scrollbar.set)

        self.canvas.pack(side="left", fill="both", expand=True)
        self.scrollbar.pack(side="right", fill="y")


class UdpReceiverGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("CarrotNavi Telemetry Diagnostics (F1 Stream)")
        self.root.geometry("850x920")
        self.root.configure(bg="#1e1e1e")
        
        # Style definition
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background="#1e1e1e")
        style.configure("TLabel", background="#1e1e1e", foreground="#ffffff", font=("Helvetica", 11))
        style.configure("TNotebook", background="#1e1e1e", borderwidth=0)
        style.configure("TNotebook.Tab", background="#2d2d2d", foreground="#ffffff", padding=[12, 6], font=("Helvetica", 10, "bold"))
        style.map("TNotebook.Tab",
                  background=[("selected", "#00f2fe"), ("active", "#4facfe")],
                  foreground=[("selected", "#121420"), ("active", "#ffffff")])
        
        self.last_payload = {}
        self.is_paused = False
        self.detail_labels = {}
        
        # Main Layout container
        main_frame = tk.Frame(self.root, bg="#1e1e1e")
        main_frame.pack(fill=tk.BOTH, expand=True, padx=15, pady=15)
        
        # Header section
        header_frame = tk.Frame(main_frame, bg="#1e1e1e")
        header_frame.pack(fill=tk.X, pady=(0, 10))
        
        self.status_label = tk.Label(header_frame, text=f"대기 중... (UDP 포트: {UDP_PORT})", bg="#1e1e1e", fg="#FF9800", font=("Helvetica", 14, "bold"))
        self.status_label.pack(side=tk.LEFT)
        
        self.heartbeat_lbl = tk.Label(header_frame, text="기기 통신 상태: 오프라인", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "italic"))
        self.heartbeat_lbl.pack(side=tk.RIGHT)
        
        # Tabbed Panel Setup
        self.notebook = ttk.Notebook(main_frame)
        self.notebook.pack(fill=tk.BOTH, expand=True, pady=5)
        
        # 1. Dashboard Tab
        self.tab_dash = tk.Frame(self.notebook, bg="#1e1e1e")
        self.notebook.add(self.tab_dash, text="대시보드 (Dashboard)")
        self.build_dashboard_tab(self.tab_dash)
        
        # 2. Metadata Tab
        self.tab_meta = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_meta, text="메타데이터")
        self.build_table_tab(self.tab_meta.scrollable_frame, "metadata")
        
        # 3. SDI Speed Tab
        self.tab_sdi = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_sdi, text="속도 & SDI")
        self.build_table_tab(self.tab_sdi.scrollable_frame, "sdi")
        
        # 4. TBT Turn Tab
        self.tab_tbt = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_tbt, text="경로 & TBT")
        self.build_table_tab(self.tab_tbt.scrollable_frame, "tbt")
        
        # 5. Destination Tab
        self.tab_dest = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_dest, text="목적지 정보")
        self.build_table_tab(self.tab_dest.scrollable_frame, "destination")
        
        # 6. GPS Location Tab
        self.tab_gps = ScrollableFrame(self.notebook)
        self.notebook.add(self.tab_gps, text="위치 & GPS")
        self.build_table_tab(self.tab_gps.scrollable_frame, "location")
        
        # Bottom Logs and Commands
        log_frame = tk.Frame(main_frame, bg="#1e1e1e")
        log_frame.pack(fill=tk.X, side=tk.BOTTOM, pady=(10, 0))
        
        log_header = tk.Frame(log_frame, bg="#1e1e1e")
        log_header.pack(fill=tk.X, pady=(0, 5))
        
        tk.Label(log_header, text="실시간 JSON 수신 로그 (최신 100 패킷)", bg="#1e1e1e", fg="#a9b7c6", font=("Helvetica", 10, "bold")).pack(side=tk.LEFT)
        
        self.btn_pause = tk.Button(log_header, text="⏸️ 로그 정지", bg="#2d2d2d", fg="#ffffff", relief=tk.FLAT, padx=10, command=self.toggle_pause)
        self.btn_pause.pack(side=tk.RIGHT, padx=5)
        
        self.btn_copy = tk.Button(log_header, text="📋 JSON 복사", bg="#2d2d2d", fg="#ffffff", relief=tk.FLAT, padx=10, command=self.copy_json)
        self.btn_copy.pack(side=tk.RIGHT, padx=5)
        
        self.btn_clear = tk.Button(log_header, text="🗑️ 로그 지우기", bg="#2d2d2d", fg="#ffffff", relief=tk.FLAT, padx=10, command=lambda: self.log_text.delete('1.0', tk.END))
        self.btn_clear.pack(side=tk.RIGHT, padx=5)
        
        self.log_text = tk.Text(log_frame, height=7, bg="#2d2d2d", fg="#a9b7c6", font=("Consolas", 10), insertbackground="white")
        self.log_text.pack(fill=tk.X)
        
        # UDP Thread listener
        self.running = True
        self.thread = threading.Thread(target=self.udp_listener_thread)
        self.thread.daemon = True
        self.thread.start()

    def build_dashboard_tab(self, tab):
        # Top Frame for Speeds
        db_header = tk.Frame(tab, bg="#1e1e1e")
        db_header.pack(fill=tk.X, pady=10, padx=10)
        
        # Speed display card
        speed_card = tk.Frame(db_header, bg="#2d2d2d", bd=1, relief=tk.SOLID)
        speed_card.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 10), pady=5)
        
        tk.Label(speed_card, text="차량 현재 속도", bg="#2d2d2d", fg="#9ca3af", font=("Helvetica", 11, "bold")).pack(pady=(12, 5))
        self.dash_speed_val = tk.Label(speed_card, text="0.0", bg="#2d2d2d", fg="#00f2fe", font=("Helvetica", 38, "bold"))
        self.dash_speed_val.pack()
        tk.Label(speed_card, text="km/h", bg="#2d2d2d", fg="#9ca3af", font=("Helvetica", 10)).pack(pady=(0, 10))
        
        # Limit speed sign card
        limit_card = tk.Frame(db_header, bg="#2d2d2d", bd=1, relief=tk.SOLID)
        limit_card.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(10, 0), pady=5)
        
        tk.Label(limit_card, text="도로 규정 제한속도", bg="#2d2d2d", fg="#9ca3af", font=("Helvetica", 11, "bold")).pack(pady=(12, 5))
        self.limit_canvas = tk.Canvas(limit_card, width=80, height=80, bg="#2d2d2d", highlightthickness=0)
        self.limit_canvas.pack(pady=(0, 10))
        draw_speed_limit(self.limit_canvas, 0)
        
        # Mid row: SDI & TBT Cards
        row1 = tk.Frame(tab, bg="#1e1e1e")
        row1.pack(fill=tk.BOTH, expand=True, pady=5, padx=10)
        
        # 1. SDI Event Card
        sdi_card = tk.LabelFrame(row1, text=" 전방 안전운행 정보 (SDI) ", bg="#1e1e1e", fg="#00f2fe", font=("Helvetica", 11, "bold"), bd=1, relief=tk.SOLID)
        sdi_card.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 10), pady=5)
        
        sdi_grid = tk.Frame(sdi_card, bg="#1e1e1e")
        sdi_grid.pack(fill=tk.BOTH, expand=True, padx=15, pady=15)
        
        tk.Label(sdi_grid, text="1차 이벤트 종류:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=0, column=0, sticky="w", pady=6)
        self.dash_sdi_type = tk.Label(sdi_grid, text="이벤트 없음", bg="#1e1e1e", fg="#ffffff", font=("Helvetica", 11, "bold"))
        self.dash_sdi_type.grid(row=0, column=1, sticky="w", padx=15, pady=6)
        
        tk.Label(sdi_grid, text="이벤트 제한속도:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=1, column=0, sticky="w", pady=6)
        self.dash_sdi_speed = tk.Label(sdi_grid, text="-", bg="#1e1e1e", fg="#FF5722", font=("Helvetica", 12, "bold"))
        self.dash_sdi_speed.grid(row=1, column=1, sticky="w", padx=15, pady=6)
        
        tk.Label(sdi_grid, text="이벤트 지점 거리:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=2, column=0, sticky="w", pady=6)
        self.dash_sdi_dist = tk.Label(sdi_grid, text="-", bg="#1e1e1e", fg="#00f2fe", font=("Helvetica", 12, "bold"))
        self.dash_sdi_dist.grid(row=2, column=1, sticky="w", padx=15, pady=6)
        
        self.dash_sdi_plus = tk.Label(sdi_grid, text="", bg="#1e1e1e", fg="#f59e0b", font=("Helvetica", 10, "italic"))
        self.dash_sdi_plus.grid(row=3, column=0, columnspan=2, sticky="w", pady=8)
        
        # 2. TBT Card
        tbt_card = tk.LabelFrame(row1, text=" 경로 회전 안내 (TBT) ", bg="#1e1e1e", fg="#00f2fe", font=("Helvetica", 11, "bold"), bd=1, relief=tk.SOLID)
        tbt_card.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(10, 0), pady=5)
        
        tbt_grid = tk.Frame(tbt_card, bg="#1e1e1e")
        tbt_grid.pack(fill=tk.BOTH, expand=True, padx=15, pady=15)
        
        tk.Label(tbt_grid, text="회전 안내 종류:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=0, column=0, sticky="w", pady=6)
        self.dash_tbt_type = tk.Label(tbt_grid, text="안내 없음", bg="#1e1e1e", fg="#ffffff", font=("Helvetica", 11, "bold"))
        self.dash_tbt_type.grid(row=0, column=1, sticky="w", padx=15, pady=6)
        
        tk.Label(tbt_grid, text="회전 안내 텍스트:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=1, column=0, sticky="w", pady=6)
        self.dash_tbt_text = tk.Label(tbt_grid, text="-", bg="#1e1e1e", fg="#ffffff", font=("Helvetica", 10, "bold"), wraplength=180, justify=tk.LEFT)
        self.dash_tbt_text.grid(row=1, column=1, sticky="w", padx=15, pady=6)
        
        tk.Label(tbt_grid, text="회점 지점 거리:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=2, column=0, sticky="w", pady=6)
        self.dash_tbt_dist = tk.Label(tbt_grid, text="-", bg="#1e1e1e", fg="#00f2fe", font=("Helvetica", 12, "bold"))
        self.dash_tbt_dist.grid(row=2, column=1, sticky="w", padx=15, pady=6)
        
        # Bottom row: Destination & Heartbeat Cards
        row2 = tk.Frame(tab, bg="#1e1e1e")
        row2.pack(fill=tk.BOTH, expand=True, pady=5, padx=10)
        
        # 3. Destination Card
        dest_card = tk.LabelFrame(row2, text=" 목적지 정보 (Destination) ", bg="#1e1e1e", fg="#00f2fe", font=("Helvetica", 11, "bold"), bd=1, relief=tk.SOLID)
        dest_card.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 10), pady=5)
        
        dest_grid = tk.Frame(dest_card, bg="#1e1e1e")
        dest_grid.pack(fill=tk.BOTH, expand=True, padx=15, pady=15)
        
        tk.Label(dest_grid, text="목적지 명칭:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=0, column=0, sticky="w", pady=6)
        self.dash_dest_name = tk.Label(dest_grid, text="목적지 설정 안 됨", bg="#1e1e1e", fg="#ffffff", font=("Helvetica", 11, "bold"))
        self.dash_dest_name.grid(row=0, column=1, sticky="w", padx=15, pady=6)
        
        tk.Label(dest_grid, text="남은 총 거리:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=1, column=0, sticky="w", pady=6)
        self.dash_dest_dist = tk.Label(dest_grid, text="-", bg="#1e1e1e", fg="#ffffff", font=("Helvetica", 11, "bold"))
        self.dash_dest_dist.grid(row=1, column=1, sticky="w", padx=15, pady=6)
        
        tk.Label(dest_grid, text="소요 예정 시간:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=2, column=0, sticky="w", pady=6)
        self.dash_dest_time = tk.Label(dest_grid, text="-", bg="#1e1e1e", fg="#ffffff", font=("Helvetica", 11, "bold"))
        self.dash_dest_time.grid(row=2, column=1, sticky="w", padx=15, pady=6)
        
        # 4. Metadata Heartbeat Card
        meta_card = tk.LabelFrame(row2, text=" 수신/메타 연동 정보 ", bg="#1e1e1e", fg="#00f2fe", font=("Helvetica", 11, "bold"), bd=1, relief=tk.SOLID)
        meta_card.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(10, 0), pady=5)
        
        meta_grid = tk.Frame(meta_card, bg="#1e1e1e")
        meta_grid.pack(fill=tk.BOTH, expand=True, padx=15, pady=15)
        
        tk.Label(meta_grid, text="현재 패킷 번호:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=0, column=0, sticky="w", pady=6)
        self.dash_meta_index = tk.Label(meta_grid, text="-", bg="#1e1e1e", fg="#00f2fe", font=("Helvetica", 11, "bold"))
        self.dash_meta_index.grid(row=0, column=1, sticky="w", padx=15, pady=6)
        
        tk.Label(meta_grid, text="내비게이션 소스:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=1, column=0, sticky="w", pady=6)
        self.dash_meta_navi = tk.Label(meta_grid, text="-", bg="#1e1e1e", fg="#ffffff", font=("Helvetica", 11, "bold"))
        self.dash_meta_navi.grid(row=1, column=1, sticky="w", padx=15, pady=6)
        
        tk.Label(meta_grid, text="도로종별 / 명칭:", bg="#1e1e1e", fg="#9ca3af", font=("Helvetica", 10, "bold")).grid(row=2, column=0, sticky="w", pady=6)
        self.dash_meta_roadname = tk.Label(meta_grid, text="-", bg="#1e1e1e", fg="#ffffff", font=("Helvetica", 11, "bold"), wraplength=180, justify=tk.LEFT)
        self.dash_meta_roadname.grid(row=2, column=1, sticky="w", padx=15, pady=6)

    def build_table_tab(self, container_frame, section_name):
        container_frame.grid_columnconfigure(0, minsize=180)
        container_frame.grid_columnconfigure(1, minsize=250)
        container_frame.grid_columnconfigure(2, minsize=350)

        # Header Row
        tk.Label(container_frame, text="JSON Key", font=("Consolas", 11, "bold"), fg="#00f2fe", bg="#1e1e1e").grid(row=0, column=0, sticky="w", padx=15, pady=10)
        tk.Label(container_frame, text="설명", font=("Helvetica", 11, "bold"), fg="#9ca3af", bg="#1e1e1e").grid(row=0, column=1, sticky="w", padx=15, pady=10)
        tk.Label(container_frame, text="실시간 값 (Value)", font=("Helvetica", 11, "bold"), fg="#4CAF50", bg="#1e1e1e").grid(row=0, column=2, sticky="w", padx=15, pady=10)
        
        # Horizontal Divider
        divider = tk.Frame(container_frame, height=2, bg="#3b82f6")
        divider.grid(row=1, column=0, columnspan=3, sticky="ew", pady=(0, 10))
        
        row_idx = 2
        for key, (label, desc) in FIELDS_SPEC[section_name].items():
            # Key Label
            tk.Label(container_frame, text=key, font=("Consolas", 10, "bold"), fg="#00f2fe", bg="#1e1e1e").grid(row=row_idx, column=0, sticky="w", padx=15, pady=6)
            # Description Label
            tk.Label(container_frame, text=desc, font=("Helvetica", 10), fg="#9ca3af", bg="#1e1e1e", wraplength=230, justify=tk.LEFT).grid(row=row_idx, column=1, sticky="w", padx=15, pady=6)
            
            # Value Label (Updates dynamically)
            val_lbl = tk.Label(container_frame, text="-", font=("Helvetica", 11, "bold"), fg="#ffffff", bg="#1e1e1e", wraplength=330, justify=tk.LEFT)
            val_lbl.grid(row=row_idx, column=2, sticky="w", padx=15, pady=6)
            self.detail_labels[key] = val_lbl
            
            row_idx += 1

    def toggle_pause(self):
        self.is_paused = not self.is_paused
        if self.is_paused:
            self.btn_pause.config(text="▶️ 로그 재개", bg="#FF9800")
            self.status_label.config(text="수신 로그 일시정지됨", fg="#FF9800")
        else:
            self.btn_pause.config(text="⏸️ 로그 정지", bg="#2d2d2d")
            self.status_label.config(text=f"정상 수신 중 (포트: {UDP_PORT})", fg="#4CAF50")

    def copy_json(self):
        if not self.last_payload:
            return
        try:
            formatted_json = json.dumps(self.last_payload, ensure_ascii=False, indent=2)
            self.root.clipboard_clear()
            self.root.clipboard_append(formatted_json)
            self.status_label.config(text="클립보드에 JSON이 복사되었습니다!", fg="#00f2fe")
            self.root.after(2000, lambda: self.status_label.config(
                text=f"정상 수신 중 (포트: {UDP_PORT})" if not self.is_paused else "수신 로그 일시정지됨",
                fg="#4CAF50" if not self.is_paused else "#FF9800"
            ))
        except Exception as e:
            print("Clipboard error:", e)

    def update_ui(self, data):
        try:
            # Update connection status
            self.heartbeat_lbl.config(text=f"마지막 패킷 수신: {datetime.now().strftime('%H:%M:%S')}", fg="#4CAF50")
            
            # 1. Update Detail Grid Views
            for section, fields in FIELDS_SPEC.items():
                for key in fields:
                    val = data.get(key, None)
                    formatted_val = "-"
                    if val is not None:
                        # Specific formatters
                        if key == "epochTime":
                            formatted_val = format_epoch_time(val)
                        elif key == "roadcate":
                            formatted_val = format_roadcate(val)
                        elif key in ["nSdiBlockType", "nSdiPlusBlockType"]:
                            formatted_val = format_block_type(val)
                        elif key in ["nGoPosTime"]:
                            formatted_val = format_time_duration(val)
                        elif key in ["nSdiType", "nSdiPlusType"]:
                            formatted_val = f"{val} ({get_sdi_type_name(val)})"
                        elif key in ["nTBTTurnType", "nTBTTurnTypeNext"]:
                            formatted_val = f"{val} ({get_tbt_turn_type_name(val)})"
                        elif key in ["nRoadLimitSpeed", "nSdiSpeedLimit", "nSdiPlusSpeedLimit", "nSdiBlockSpeed", "nSdiPlusBlockSpeed", "nPosSpeed", "gps_speed"]:
                            formatted_val = f"{val} km/h"
                        elif key in ["nSdiDist", "nSdiPlusDist", "nSdiBlockDist", "nSdiPlusBlockDist", "nTBTDist", "nTBTDistNext", "nTBTNextRoadWidth", "accuracy"]:
                            formatted_val = f"{val} m"
                        elif key == "nGoPosDist":
                            if val >= 1000:
                                formatted_val = f"{val:,} m ({val/1000.0:.2f} km)"
                            else:
                                formatted_val = f"{val} m"
                        elif isinstance(val, float):
                            formatted_val = f"{val:.6f}"
                        else:
                            formatted_val = str(val)
                    
                    if key in self.detail_labels:
                        self.detail_labels[key].config(text=formatted_val)
            
            # 2. Update Dashboard Overview
            # Current Speed
            curr_speed = data.get("nPosSpeed", data.get("gps_speed", 0.0))
            self.dash_speed_val.config(text=f"{curr_speed:.1f}")
            
            # Speed Limit canvas
            road_limit = data.get("nRoadLimitSpeed", 0)
            draw_speed_limit(self.limit_canvas, road_limit)
            
            # SDI safety info
            sdi_type = data.get("nSdiType", 0)
            if sdi_type > 0:
                sdi_name = get_sdi_type_name(sdi_type)
                sdi_speed = data.get("nSdiSpeedLimit", 0)
                sdi_dist = data.get("nSdiDist", 0)
                
                self.dash_sdi_type.config(text=f"{sdi_name} ({sdi_type})")
                self.dash_sdi_speed.config(text=f"{sdi_speed} km/h" if sdi_speed > 0 else "-")
                self.dash_sdi_dist.config(text=f"{sdi_dist} m")
                
                # Visual Highlight based on SDI Type
                if sdi_type == 22:
                    self.dash_sdi_type.config(fg="#FF9800") # Orange for Speed Bump
                elif sdi_type in [1, 2, 3, 4, 7, 8, 75, 76]:
                    self.dash_sdi_type.config(fg="#F44336") # Red for Speed Cam
                elif sdi_type in [20, 21, 33]:
                    self.dash_sdi_type.config(fg="#E91E63") # Pink for School zone
                else:
                    self.dash_sdi_type.config(fg="#00f2fe")
            else:
                self.dash_sdi_type.config(text="이벤트 없음", fg="#ffffff")
                self.dash_sdi_speed.config(text="-")
                self.dash_sdi_dist.config(text="-")
                
            # Secondary SDI event
            plus_type = data.get("nSdiPlusType", 0)
            if plus_type > 0:
                plus_name = get_sdi_type_name(plus_type)
                plus_speed = data.get("nSdiPlusSpeedLimit", 0)
                plus_dist = data.get("nSdiPlusDist", 0)
                self.dash_sdi_plus.config(text=f"▶ 연속 이벤트: {plus_name} ({plus_speed} km/h, {plus_dist}m)")
            else:
                self.dash_sdi_plus.config(text="")
                
            # TBT instructions
            tbt_type = data.get("nTBTTurnType", -1)
            if tbt_type >= 0:
                tbt_name = get_tbt_turn_type_name(tbt_type)
                tbt_text = data.get("szTBTMainText", "-")
                tbt_dist = data.get("nTBTDist", 0)
                
                self.dash_tbt_type.config(text=f"{tbt_name} ({tbt_type})")
                self.dash_tbt_text.config(text=tbt_text)
                self.dash_tbt_dist.config(text=f"{tbt_dist} m")
            else:
                self.dash_tbt_type.config(text="안내 없음")
                self.dash_tbt_text.config(text="-")
                self.dash_tbt_dist.config(text="-")
                
            # Destination
            goal_name = data.get("szGoalName", "")
            if goal_name:
                goal_dist = data.get("nGoPosDist", 0)
                goal_time = data.get("nGoPosTime", 0)
                
                self.dash_dest_name.config(text=goal_name)
                if goal_dist >= 1000:
                    self.dash_dest_dist.config(text=f"{goal_dist/1000.0:.2f} km")
                else:
                    self.dash_dest_dist.config(text=f"{goal_dist} m")
                
                # Format time
                self.dash_dest_time.config(text=format_time_duration(goal_time).split(" (")[-1].replace(")", ""))
            else:
                self.dash_dest_name.config(text="목적지 설정 안 됨")
                self.dash_dest_dist.config(text="-")
                self.dash_dest_time.config(text="-")
                
            # Heartbeat info
            self.dash_meta_index.config(text=str(data.get("carrotIndex", "-")))
            self.dash_meta_navi.config(text=str(data.get("navitype", "-")))
            
            road_name = data.get("szPosRoadName", "")
            road_cate = data.get("roadcate")
            road_cate_str = "일반"
            if road_cate is not None:
                if road_cate == 0:
                    road_cate_str = "고속국도"
                elif road_cate == 1:
                    road_cate_str = "도시고속"
                elif road_cate == 8:
                    road_cate_str = "이면도로"
            self.dash_meta_roadname.config(text=f"{road_name} ({road_cate_str})" if road_name else f"미설정 ({road_cate_str})")
            
            # 3. Log to Text widget if not paused
            if not self.is_paused:
                timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
                log_entry = f"[{timestamp}] {json.dumps(data, ensure_ascii=False)}\n"
                self.log_text.insert(tk.END, log_entry)
                self.log_text.see(tk.END)
                
                # Keep log size limited
                lines = int(self.log_text.index('end-1c').split('.')[0])
                if lines > 100:
                    self.log_text.delete('1.0', f'{lines - 100}.0')
                    
        except Exception as e:
            print("UI Update Exception:", e)

    def udp_listener_thread(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            self.sock.bind((UDP_IP, UDP_PORT))
            self.root.after(0, lambda: self.status_label.config(text=f"정상 수신 중 (포트: {UDP_PORT})", fg="#4CAF50"))
        except Exception as e:
            self.root.after(0, lambda e=e: self.status_label.config(text=f"포트 바인딩 실패: {e}", fg="#F44336"))
            return

        while self.running:
            try:
                data, addr = self.sock.recvfrom(4096)
                if not self.running:
                    break
                payload = data.decode('utf-8')
                json_data = json.loads(payload)
                self.last_payload = json_data
                self.root.after(0, self.update_ui, json_data)
            except json.JSONDecodeError:
                pass
            except Exception as e:
                if self.running:
                    print("UDP recv error:", e)

    def on_closing(self):
        self.running = False
        try:
            self.sock.close()
        except Exception:
            pass
        try:
            # Dummy UDP packet to unblock recvfrom loop
            dummy_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            dummy_sock.sendto(b"{}", ("127.0.0.1", UDP_PORT))
            dummy_sock.close()
        except Exception:
            pass
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    app = UdpReceiverGUI(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    root.mainloop()