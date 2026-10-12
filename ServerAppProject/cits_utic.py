import os
import sqlite3
import math
import time
import json
import logging
import urllib.request
import urllib.parse
from datetime import datetime
from threading import Thread, Lock
from flask import Blueprint, request, jsonify

logger = logging.getLogger("cits_utic")
logger.setLevel(logging.INFO)

cits_bp = Blueprint('cits', __name__, url_prefix='/api/cits')

DEFAULT_API_KEY = "g1rRQAlluwzh3DW76Yff4XJADA5TCqFwZECNzgfXXrI"
UTIC_BASE_URL = "http://tsihub.utic.go.kr/tsi/api"

DB_PATH = os.path.join(os.path.dirname(__file__), 'data', 'cits_utic.db')

sync_lock = Lock()
is_syncing = False
sync_status = {
    "intersections_count": 0,
    "plans_count": 0,
    "last_sync_time": None,
    "is_syncing": False,
    "error": None
}

def get_db():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=30.0)
    conn.row_factory = sqlite3.Row
    return conn

def init_cits_db():
    conn = get_db()
    with conn:
        conn.execute('''
            CREATE TABLE IF NOT EXISTS intersections (
                int_no TEXT PRIMARY KEY,
                int_nm TEXT,
                region_cd TEXT,
                lat REAL,
                lon REAL,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_inter_lat_lon ON intersections(lat, lon)')

        conn.execute('''
            CREATE TABLE IF NOT EXISTS tod_plans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                int_no TEXT,
                region_cd TEXT,
                int_nm TEXT,
                plan_no TEXT,
                plan_idx_no TEXT,
                oper_hh INTEGER,
                oper_mi INTEGER,
                cycle_val INTEGER,
                offset_val INTEGER,
                a_phases TEXT,
                b_phases TEXT,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_tod_int_no ON tod_plans(int_no, oper_hh, oper_mi)')
    conn.close()
    update_cached_counts()

def update_cached_counts():
    global sync_status
    try:
        conn = get_db()
        c_inter = conn.execute('SELECT COUNT(*) FROM intersections').fetchone()[0]
        c_plans = conn.execute('SELECT COUNT(*) FROM tod_plans').fetchone()[0]
        conn.close()
        sync_status["intersections_count"] = c_inter
        sync_status["plans_count"] = c_plans
    except Exception as e:
        logger.error(f"Error updating cached counts: {e}")

# 거리 계산 함수 (Haversine, 미터 단위)
def calculate_distance(lat1, lon1, lat2, lon2):
    R = 6371000.0  # 지구 반지름 (m)
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2.0) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2.0) ** 2
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return R * c

# 방위각 계산 함수 (0 ~ 360도)
def calculate_bearing(lat1, lon1, lat2, lon2):
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_lambda = math.radians(lon2 - lon1)

    y = math.sin(delta_lambda) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(delta_lambda)
    theta = math.atan2(y, x)
    return (math.degrees(theta) + 360.0) % 360.0

# UTIC 데이터 동기화 백그라운드 태스크
def sync_utic_data_task(api_key=DEFAULT_API_KEY, force=False):
    global is_syncing, sync_status
    with sync_lock:
        if is_syncing:
            return
        is_syncing = True
        sync_status["is_syncing"] = True
        sync_status["error"] = None

    try:
        conn = get_db()
        # 1. 교차로 정보 수집 (CrossRoadInfoService/getCrossRoadInfoList)
        inter_count = conn.execute('SELECT COUNT(*) FROM intersections').fetchone()[0]
        if inter_count == 0 or force:
            logger.info("Starting synchronization of UTIC intersection list...")
            page = 1
            max_pages = 65
            inserted = 0
            while page <= max_pages:
                url = f"{UTIC_BASE_URL}/CrossRoadInfoService/getCrossRoadInfoList?serviceKey={api_key}&type=json&pageNo={page}&numOfRows=100"
                try:
                    req = urllib.request.Request(url, headers={"User-Agent": "CarrotNavi-Proxy/1.0"})
                    with urllib.request.urlopen(req, timeout=10) as resp:
                        data = json.loads(resp.read().decode('utf-8'))
                        if len(data) <= 1:
                            break
                        items = data[1:]
                        with conn:
                            for it in items:
                                int_no = it.get('INT_NO')
                                int_nm = it.get('INT_NM')
                                region_cd = it.get('REGION_CD')
                                raw_y = float(it.get('Y_COORD', 0))
                                raw_x = float(it.get('X_COORD', 0))
                                lat = raw_y / 1e7
                                lon = raw_x / 1e7
                                if int_no and lat > 0 and lon > 0:
                                    conn.execute('''
                                        INSERT OR REPLACE INTO intersections (int_no, int_nm, region_cd, lat, lon, updated_at)
                                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                                    ''', (int_no, int_nm, region_cd, lat, lon))
                                    inserted += 1
                        page += 1
                except Exception as e:
                    logger.error(f"Error fetching intersections page {page}: {e}")
                    break
            logger.info(f"Intersections sync finished: {inserted} records inserted.")

        # 2. TOD 운영 계획 수집 (PlanCrossRoadInfoService/getPlanCROPInfo)
        plans_count = conn.execute('SELECT COUNT(*) FROM tod_plans').fetchone()[0]
        if plans_count == 0 or force:
            logger.info("Starting synchronization of UTIC TOD plans (initial batch)...")
            page = 1
            max_plan_pages = 100  # 처음 100페이지 (10,000건) 수집
            inserted_plans = 0
            while page <= max_plan_pages:
                url = f"{UTIC_BASE_URL}/PlanCrossRoadInfoService/getPlanCROPInfo?serviceKey={api_key}&type=json&pageNo={page}&numOfRows=100"
                try:
                    req = urllib.request.Request(url, headers={"User-Agent": "CarrotNavi-Proxy/1.0"})
                    with urllib.request.urlopen(req, timeout=10) as resp:
                        data = json.loads(resp.read().decode('utf-8'))
                        if len(data) <= 1:
                            break
                        items = data[1:]
                        with conn:
                            for it in items:
                                int_no = it.get('INT_NO')
                                region_cd = it.get('REGION_CD')
                                int_nm = it.get('INT_NM')
                                plan_no = it.get('INT_PLAN_NO')
                                plan_idx_no = it.get('INT_PLAN_IDX_NO')
                                def safe_int(v, d=0):
                                    try:
                                        return int(v) if v is not None and str(v).strip() != '' else d
                                    except (ValueError, TypeError):
                                        return d

                                oper_hh = safe_int(it.get('OPER_PLAN_HH'), 0)
                                oper_mi = safe_int(it.get('OPER_PLAN_MI'), 0)
                                cycle_val = safe_int(it.get('INT_OPER_CYCLE_VAL'), 120)
                                offset_val = safe_int(it.get('INT_OPER_OFFSET_VAL'), 0)
                                
                                a_phases = [safe_int(it.get(f'A_RING_{i}_PHASE_VAL'), 0) for i in range(1, 9)]
                                b_phases = [safe_int(it.get(f'B_RING_{i}_PHASE_VAL'), 0) for i in range(1, 9)]

                                if int_no and cycle_val > 0:
                                    conn.execute('''
                                        INSERT INTO tod_plans (int_no, region_cd, int_nm, plan_no, plan_idx_no,
                                                               oper_hh, oper_mi, cycle_val, offset_val, a_phases, b_phases)
                                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    ''', (int_no, region_cd, int_nm, plan_no, plan_idx_no,
                                          oper_hh, oper_mi, cycle_val, offset_val, json.dumps(a_phases), json.dumps(b_phases)))
                                    inserted_plans += 1
                        page += 1
                except Exception as e:
                    logger.error(f"Error fetching TOD plans page {page}: {e}")
                    break
            logger.info(f"TOD plans sync finished: {inserted_plans} records inserted.")

        conn.close()
        update_cached_counts()
        sync_status["last_sync_time"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    except Exception as e:
        logger.error(f"Sync error: {e}", exc_info=True)
        sync_status["error"] = str(e)
    finally:
        with sync_lock:
            is_syncing = False
            sync_status["is_syncing"] = False

# 실시간 신호 상태 및 잔여시간 계산 함수
def calculate_signal_state(int_no, now_dt=None):
    if now_dt is None:
        now_dt = datetime.now()

    conn = get_db()
    current_time_minutes = now_dt.hour * 60 + now_dt.minute

    # 해당 교차로의 최적 TOD 계획 조회 (현재 시각 이전의 가장 최근 계획)
    plan = conn.execute('''
        SELECT * FROM tod_plans 
        WHERE int_no = ? AND (oper_hh * 60 + oper_mi) <= ?
        ORDER BY (oper_hh * 60 + oper_mi) DESC LIMIT 1
    ''', (int_no, current_time_minutes)).fetchone()

    # 만약 현재 시각 이전 계획이 없으면 (예: 새벽), 하루 중 가장 마지막 계획 사용
    if not plan:
        plan = conn.execute('''
            SELECT * FROM tod_plans 
            WHERE int_no = ? 
            ORDER BY (oper_hh * 60 + oper_mi) DESC LIMIT 1
        ''', (int_no,)).fetchone()

    conn.close()

    if plan:
        cycle_val = plan['cycle_val'] if plan['cycle_val'] > 0 else 120
        offset_val = plan['offset_val']
        a_phases = json.loads(plan['a_phases'])
        # 유효한 현시만 추출 (초 > 0)
        valid_phases = [p for p in a_phases if p > 0]
        if not valid_phases or sum(valid_phases) == 0:
            valid_phases = [35, 25, 30, 30]
            cycle_val = 120
    else:
        # DB에 세부 TOD 계획이 없는 교차로는 표준 120초 4현시 기본 모델 적용
        cycle_val = 120
        offset_val = 0
        valid_phases = [35, 25, 30, 30]

    # 오늘 00:00:00 기준 현재 초 계산
    total_sec = now_dt.hour * 3600 + now_dt.minute * 60 + now_dt.second
    # 주기 내 상대 시각 계산 (옵셋 고려)
    t = (total_sec - offset_val) % cycle_val
    if t < 0:
        t += cycle_val

    # 현재 현시 및 남은 초 계산
    cumulative = 0
    current_phase_idx = 0
    phase_remain = 0

    for idx, p_len in enumerate(valid_phases):
        if cumulative <= t < cumulative + p_len:
            current_phase_idx = idx + 1
            phase_remain = (cumulative + p_len) - t
            break
        cumulative += p_len

    if current_phase_idx == 0:
        current_phase_idx = 1
        phase_remain = max(1, valid_phases[0] - t)

    # 신호등 색상 매핑:
    # 1현시: 녹색 직진 (A링 주방향)
    # 2현시: 좌회전 (A링 주방향 좌회전)
    # 3현시, 4현시: 적색 대기 (부방향 통행)
    is_red = False
    is_yellow = False
    is_green = False
    is_left = False

    if current_phase_idx == 1:
        if phase_remain <= 3:
            is_yellow = True
        else:
            is_green = True
    elif current_phase_idx == 2:
        if phase_remain <= 3:
            is_yellow = True
        else:
            is_left = True
    else:
        is_red = True

    return {
        "is_red": is_red,
        "is_yellow": is_yellow,
        "is_green": is_green,
        "is_left": is_left,
        "remain_seconds": phase_remain,
        "current_phase": current_phase_idx,
        "total_phases": len(valid_phases),
        "cycle_seconds": cycle_val
    }

# ----------------- API Endpoints -----------------

@cits_bp.route('/status', methods=['GET'])
def get_cits_status():
    update_cached_counts()
    return jsonify({
        "status": "success",
        "data": sync_status
    })

@cits_bp.route('/sync', methods=['POST'])
def trigger_sync():
    global is_syncing
    if is_syncing:
        return jsonify({"status": "busy", "message": "Synchronization is already in progress."}), 429
    
    api_key = request.args.get('api_key') or (request.json.get('api_key') if request.is_json else None) or DEFAULT_API_KEY
    force = request.args.get('force', 'false').lower() == 'true'

    t = Thread(target=sync_utic_data_task, args=(api_key, force), daemon=True)
    t.start()
    return jsonify({"status": "started", "message": "UTIC data synchronization started in background."})

@cits_bp.route('/nearby_signal', methods=['GET'])
def get_nearby_signal():
    """
    차량 위치 및 진행방향을 받아 가장 가까운 전방 교차로와 실시간 신호 상태를 반환
    Query:
      lat: 위도 (WGS84)
      lon: 경도 (WGS84)
      heading: 차량 진행각도 (0~360도, 선택)
      max_dist: 최대 탐색거리 (미터, 기본 500m)
    """
    try:
        lat = float(request.args.get('lat', 0))
        lon = float(request.args.get('lon', 0))
        heading_str = request.args.get('heading')
        heading = float(heading_str) if heading_str is not None else None
        max_dist = float(request.args.get('max_dist', 500.0))
    except (ValueError, TypeError):
        return jsonify({"status": "error", "message": "Invalid coordinates"}), 400

    if lat <= 0 or lon <= 0:
        return jsonify({"status": "error", "message": "Coordinates required"}), 400

    # 1도 위도 ~= 111km, 대략적인 bounding box 필터링 (반경 1km)
    delta_lat = 0.01
    delta_lon = 0.015

    conn = get_db()
    rows = conn.execute('''
        SELECT int_no, int_nm, region_cd, lat, lon 
        FROM intersections 
        WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
    ''', (lat - delta_lat, lat + delta_lat, lon - delta_lon, lon + delta_lon)).fetchall()
    conn.close()

    nearest = None
    min_dist = max_dist

    for row in rows:
        d = calculate_distance(lat, lon, row['lat'], row['lon'])
        if d > max_dist or d < 10.0:  # 10m 이내(이미 통과) 제외
            continue

        if heading is not None:
            bearing = calculate_bearing(lat, lon, row['lat'], row['lon'])
            angle_diff = abs((bearing - heading + 540) % 360 - 180)
            if angle_diff > 65.0:  # 전방 ±65도 이내가 아니면 패스
                continue

        if d < min_dist:
            min_dist = d
            nearest = row

    if not nearest:
        return jsonify({
            "status": "success",
            "matched": False,
            "message": "No nearby forward intersection found"
        })

    # 매칭된 교차로의 현재 실시간 신호 계산
    signal_info = calculate_signal_state(nearest['int_no'])

    return jsonify({
        "status": "success",
        "matched": True,
        "intersection": {
            "id": nearest['int_no'],
            "name": nearest['int_nm'],
            "region_cd": nearest['region_cd'],
            "lat": nearest['lat'],
            "lon": nearest['lon'],
            "distance": round(min_dist, 1)
        },
        "signal": signal_info
    })

@cits_bp.route('/intersections', methods=['GET'])
def get_intersections():
    """
    주변 또는 지역 교차로 목록 조회 (지도 마커 표시용)
    """
    try:
        lat = float(request.args.get('lat', 0))
        lon = float(request.args.get('lon', 0))
        radius = float(request.args.get('radius', 1000.0))  # 기본 1km
    except (ValueError, TypeError):
        lat = 0
        lon = 0
        radius = 1000.0

    conn = get_db()
    if lat > 0 and lon > 0:
        delta_lat = radius / 111000.0
        delta_lon = radius / (111000.0 * math.cos(math.radians(lat)))
        rows = conn.execute('''
            SELECT int_no, int_nm, region_cd, lat, lon 
            FROM intersections 
            WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
        ''', (lat - delta_lat, lat + delta_lat, lon - delta_lon, lon + delta_lon)).fetchall()
    else:
        region_cd = request.args.get('region_cd')
        if region_cd:
            rows = conn.execute('SELECT int_no, int_nm, region_cd, lat, lon FROM intersections WHERE region_cd = ?', (region_cd,)).fetchall()
        else:
            rows = conn.execute('SELECT int_no, int_nm, region_cd, lat, lon FROM intersections LIMIT 200').fetchall()
    conn.close()

    result = []
    for r in rows:
        result.append({
            "id": r['int_no'],
            "name": r['int_nm'],
            "region_cd": r['region_cd'],
            "lat": r['lat'],
            "lon": r['lon']
        })

    return jsonify({
        "status": "success",
        "count": len(result),
        "intersections": result
    })
