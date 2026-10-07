import os
import sqlite3
import time
from datetime import datetime
from flask import Flask, request, jsonify, render_template, Response

app = Flask(__name__)
app.config['TEMPLATES_AUTO_RELOAD'] = True

if not os.path.exists('data'):
    os.makedirs('data')
DB_FILE = 'data/logs.db'

def get_db_connection():
    conn = sqlite3.connect(DB_FILE, timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA busy_timeout = 30000')
    return conn

def init_db():
    conn = get_db_connection()
    try:
        conn.execute('PRAGMA journal_mode=WAL')
        conn.execute('PRAGMA synchronous=NORMAL')
    except Exception:
        pass

    # logs table
    conn.execute('''
        CREATE TABLE IF NOT EXISTS logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            timestamp TEXT NOT NULL,
            app_version TEXT,
            level TEXT,
            message TEXT,
            stacktrace TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    # devices table to track logging status
    conn.execute('''
        CREATE TABLE IF NOT EXISTS devices (
            device_id TEXT PRIMARY KEY,
            logging_enabled INTEGER DEFAULT 0,
            last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            alias TEXT
        )
    ''')
    
    # Add alias, app_version, is_pinned columns if they don't exist (migration)
    for col, col_type, default in [
        ('alias', 'TEXT', None),
        ('app_version', 'TEXT', None),
        ('is_pinned', 'INTEGER', '0')
    ]:
        try:
            default_clause = f" DEFAULT {default}" if default is not None else ""
            conn.execute(f'ALTER TABLE devices ADD COLUMN {col} {col_type}{default_clause}')
        except sqlite3.OperationalError:
            pass # Column already exists
        
    try:
        existing_indexes = {row['name'] for row in conn.execute("PRAGMA index_list('logs')").fetchall()}
        if 'idx_logs_device_id_desc' not in existing_indexes:
            conn.execute('CREATE INDEX IF NOT EXISTS idx_logs_device_id_desc ON logs(device_id, id DESC)')
        if 'idx_logs_device_time' not in existing_indexes:
            conn.execute('CREATE INDEX IF NOT EXISTS idx_logs_device_time ON logs(device_id, timestamp)')
        if 'idx_logs_device_level' not in existing_indexes:
            conn.execute('CREATE INDEX IF NOT EXISTS idx_logs_device_level ON logs(device_id, level)')
    except Exception as e:
        print(f"Index check/creation notice: {e}")
        
    conn.commit()
    conn.close()

init_db()

_last_cleanup_time = 0

def maybe_cleanup_old_logs():
    global _last_cleanup_time
    now = time.time()
    # Run cleanup at most once every 6 hours (21600 seconds)
    if now - _last_cleanup_time > 21600:
        _last_cleanup_time = now
        try:
            conn = get_db_connection()
            conn.execute("DELETE FROM logs WHERE created_at < datetime('now', '-7 days')")
            conn.commit()
            conn.close()
        except Exception as e:
            print(f"Periodic log cleanup error: {e}")

@app.route('/')
def index():
    conn = get_db_connection()
    devices = conn.execute('''
        SELECT device_id, logging_enabled, alias, app_version, is_pinned,
        datetime(last_seen, '+9 hours') AS last_seen,
        (julianday('now') - julianday(last_seen)) * 86400 AS seconds_since_last_seen 
        FROM devices ORDER BY is_pinned DESC, last_seen DESC
    ''').fetchall()
    conn.close()
    
    total_devices = len(devices)
    online_devices = sum(1 for d in devices if d['seconds_since_last_seen'] is not None and d['seconds_since_last_seen'] < 30)
    offline_devices = total_devices - online_devices
    
    return render_template('index.html', devices=devices, total=total_devices, online=online_devices, offline=offline_devices)

@app.route('/device/<device_id>')
def device_logs(device_id):
    page = request.args.get('page', 1, type=int)
    start_date = request.args.get('start_date')
    end_date = request.args.get('end_date')
    q = request.args.get('q', '').strip()
    category = request.args.get('category', 'all').strip()
    
    per_page = 100
    offset = (page - 1) * per_page
    
    query = 'SELECT * FROM logs WHERE device_id = ?'
    count_query = 'SELECT COUNT(*) FROM logs WHERE device_id = ?'
    params = [device_id]
    
    if start_date:
        query += ' AND timestamp >= ?'
        count_query += ' AND timestamp >= ?'
        if len(start_date) == 16: # format: YYYY-MM-DDTHH:MM
            params.append(start_date + ':00')
        else:
            params.append(start_date)
        
    if end_date:
        query += ' AND timestamp <= ?'
        count_query += ' AND timestamp <= ?'
        if len(end_date) == 16:
            params.append(end_date + ':59')
        else:
            params.append(end_date)
            
    if q:
        query += ' AND (message LIKE ? OR stacktrace LIKE ?)'
        count_query += ' AND (message LIKE ? OR stacktrace LIKE ?)'
        params.extend([f'%{q}%', f'%{q}%'])

    if category == 'camera':
        clause = ' AND (message LIKE "%[CAMERA]%" OR message LIKE "%[SDI]%" OR message LIKE "%카메라%" OR message LIKE "%단속%")'
        query += clause
        count_query += clause
    elif category == 'route':
        clause = ' AND (message LIKE "%[ROUTE]%" OR message LIKE "%경로%")'
        query += clause
        count_query += clause
    elif category == 'error':
        clause = ' AND level IN ("WARN", "ERROR", "FATAL")'
        query += clause
        count_query += clause
    elif category == 'udp':
        clause = ' AND (message LIKE "%[UDP]%" OR message LIKE "%Udp%")'
        query += clause
        count_query += clause
    elif category == 'speed':
        clause = ' AND (message LIKE "%[SPEED_LIMIT]%" OR message LIKE "%제한속도%")'
        query += clause
        count_query += clause
        
    # Order by id DESC (uses primary key/index, orders of magnitude faster than created_at)
    query += ' ORDER BY id DESC LIMIT ? OFFSET ?'
    
    conn = get_db_connection()
    logs = conn.execute(query, params + [per_page, offset]).fetchall()
    
    # Fast count optimization
    if page == 1 and len(logs) < per_page:
        total_count = len(logs)
    else:
        total_count = conn.execute(count_query, params).fetchone()[0]
        
    total_pages = (total_count + per_page - 1) // per_page if total_count > 0 else 1
    
    device = conn.execute('SELECT * FROM devices WHERE device_id = ?', (device_id,)).fetchone()
    conn.close()
    
    device_alias = device['alias'] if device and device['alias'] else device_id
    
    return render_template('device_logs.html', logs=logs, selected_device=device_id, device_alias=device_alias, page=page, total_pages=total_pages, start_date=start_date, end_date=end_date, q=q, category=category)

@app.route('/device/<device_id>/download')
def download_logs(device_id):
    start_date = request.args.get('start_date')
    end_date = request.args.get('end_date')
    q = request.args.get('q', '').strip()
    category = request.args.get('category', 'all').strip()
    clean = request.args.get('clean', '0') == '1'
    
    query = 'SELECT timestamp, level, message, stacktrace FROM logs WHERE device_id = ?'
    params = [device_id]
    
    if start_date:
        query += ' AND timestamp >= ?'
        if len(start_date) == 16:
            params.append(start_date + ':00')
        else:
            params.append(start_date)
            
    if end_date:
        query += ' AND timestamp <= ?'
        if len(end_date) == 16:
            params.append(end_date + ':59')
        else:
            params.append(end_date)

    if q:
        query += ' AND (message LIKE ? OR stacktrace LIKE ?)'
        params.extend([f'%{q}%', f'%{q}%'])

    if category == 'camera':
        query += ' AND (message LIKE "%[CAMERA]%" OR message LIKE "%[SDI]%" OR message LIKE "%카메라%" OR message LIKE "%단속%")'
    elif category == 'route':
        query += ' AND (message LIKE "%[ROUTE]%" OR message LIKE "%경로%")'
    elif category == 'error':
        query += ' AND level IN ("WARN", "ERROR", "FATAL")'
    elif category == 'udp':
        query += ' AND (message LIKE "%[UDP]%" OR message LIKE "%Udp%")'
    elif category == 'speed':
        query += ' AND (message LIKE "%[SPEED_LIMIT]%" OR message LIKE "%제한속도%")'

    if clean:
        query += ' AND message NOT LIKE "%requestLayout()%" AND message NOT LIKE "%TrafficStats%" AND message NOT LIKE "%AidlConversionCppNdk%"'
            
    query += ' ORDER BY id ASC'
    
    def generate():
        conn = get_db_connection()
        cursor = conn.cursor()
        cursor.execute(query, params)
        while True:
            rows = cursor.fetchmany(1000)
            if not rows:
                break
            for log in rows:
                ts = (log['timestamp'] or '')[:19].replace('T', ' ')
                line = f"[{ts}] {log['level']} : {log['message']}"
                if log['stacktrace']:
                    line += f"\n{log['stacktrace']}"
                yield line + '\n'
        conn.close()
        
    suffix = "_clean" if clean else ""
    if start_date or end_date or q or category != 'all':
        filename = f"logs_{device_id}_filtered{suffix}.txt"
    else:
        filename = f"logs_{device_id}{suffix}.txt"
        
    return Response(generate(), mimetype='text/plain', headers={"Content-Disposition": f"attachment;filename={filename}"})

@app.route('/api/device/<device_id>/latest')
def get_latest_logs(device_id):
    since_id = request.args.get('since_id', 0, type=int)
    limit = min(request.args.get('limit', 50, type=int), 200)
    category = request.args.get('category', 'all').strip()
    q = request.args.get('q', '').strip()
    
    query = 'SELECT id, device_id, timestamp, app_version, level, message, stacktrace, created_at FROM logs WHERE device_id = ? AND id > ?'
    params = [device_id, since_id]
    
    if q:
        query += ' AND (message LIKE ? OR stacktrace LIKE ?)'
        params.extend([f'%{q}%', f'%{q}%'])

    if category == 'camera':
        query += ' AND (message LIKE "%[CAMERA]%" OR message LIKE "%[SDI]%" OR message LIKE "%카메라%" OR message LIKE "%단속%")'
    elif category == 'route':
        query += ' AND (message LIKE "%[ROUTE]%" OR message LIKE "%경로%")'
    elif category == 'error':
        query += ' AND level IN ("WARN", "ERROR", "FATAL")'
    elif category == 'udp':
        query += ' AND (message LIKE "%[UDP]%" OR message LIKE "%Udp%")'
    elif category == 'speed':
        query += ' AND (message LIKE "%[SPEED_LIMIT]%" OR message LIKE "%제한속도%")'
        
    query += ' ORDER BY id DESC LIMIT ?'
    params.append(limit)
    
    conn = get_db_connection()
    logs = conn.execute(query, params).fetchall()
    conn.close()
    return jsonify([dict(log) for log in logs])

@app.route('/api/config', methods=['GET'])
def get_config():
    device_id = request.args.get('device_id')
    app_version = request.args.get('app_version', 'unknown')
    
    if not device_id:
        return jsonify({'error': 'device_id required'}), 400
        
    conn = get_db_connection()
    device = conn.execute('SELECT logging_enabled FROM devices WHERE device_id = ?', (device_id,)).fetchone()
    
    if not device:
        # Register new device with logging disabled by default
        conn.execute('INSERT INTO devices (device_id, logging_enabled, app_version) VALUES (?, 0, ?)', (device_id, app_version))
        conn.commit()
        logging_enabled = 0
    else:
        # Update last seen and app version
        conn.execute('UPDATE devices SET last_seen = CURRENT_TIMESTAMP, app_version = ? WHERE device_id = ?', (app_version, device_id))
        conn.commit()
        logging_enabled = device['logging_enabled']
        
    conn.close()
    return jsonify({'logging_enabled': bool(logging_enabled)}), 200

@app.route('/api/devices/<device_id>/alias', methods=['POST'])
def set_device_alias(device_id):
    data = request.json
    alias = data.get('alias', '').strip()
    
    conn = get_db_connection()
    if alias:
        conn.execute('UPDATE devices SET alias = ? WHERE device_id = ?', (alias, device_id))
    else:
        conn.execute('UPDATE devices SET alias = NULL WHERE device_id = ?', (device_id,))
    conn.commit()
    conn.close()
    return jsonify({'status': 'success', 'alias': alias})

@app.route('/api/devices/<device_id>/pin', methods=['POST'])
def toggle_device_pin(device_id):
    data = request.json or {}
    state = data.get('state')
    
    conn = get_db_connection()
    device = conn.execute('SELECT is_pinned FROM devices WHERE device_id = ?', (device_id,)).fetchone()
    if device:
        new_status = int(state) if state is not None else (1 if not device['is_pinned'] else 0)
        conn.execute('UPDATE devices SET is_pinned = ? WHERE device_id = ?', (new_status, device_id))
        conn.commit()
        conn.close()
        return jsonify({'status': 'success', 'is_pinned': new_status})
    conn.close()
    return jsonify({'error': 'Device not found'}), 404

@app.route('/api/devices/<device_id>/delete', methods=['POST'])
def delete_device(device_id):
    conn = get_db_connection()
    conn.execute('DELETE FROM logs WHERE device_id = ?', (device_id,))
    conn.execute('DELETE FROM devices WHERE device_id = ?', (device_id,))
    conn.commit()
    conn.close()
    return jsonify({'status': 'success'})

@app.route('/api/logs', methods=['POST'])
def receive_logs():
    data = request.json
    if not data or 'device_id' not in data:
        return jsonify({'error': 'Invalid payload'}), 400

    device_id = data['device_id']
    
    conn = get_db_connection()
    device = conn.execute('SELECT logging_enabled FROM devices WHERE device_id = ?', (device_id,)).fetchone()
    if device and not device['logging_enabled']:
        conn.close()
        return jsonify({'status': 'ignored'}), 200

    conn.execute('''
        INSERT INTO logs (device_id, timestamp, app_version, level, message, stacktrace)
        VALUES (?, ?, ?, ?, ?, ?)
    ''', (
        device_id,
        data.get('timestamp', datetime.now().isoformat()),
        data.get('app_version', 'unknown'),
        data.get('level', 'INFO'),
        data.get('message', ''),
        data.get('stacktrace', '')
    ))
    
    conn.execute('''
        INSERT INTO devices (device_id, logging_enabled, last_seen) 
        VALUES (?, 0, CURRENT_TIMESTAMP)
        ON CONFLICT(device_id) DO UPDATE SET last_seen=CURRENT_TIMESTAMP
    ''', (device_id,))
    
    conn.commit()
    conn.close()
    
    maybe_cleanup_old_logs()
    return jsonify({'status': 'success'}), 200

@app.route('/api/logs/batch', methods=['POST'])
def receive_logs_batch():
    data = request.json
    if not data or 'device_id' not in data or 'logs' not in data:
        return jsonify({'error': 'Invalid payload'}), 400

    device_id = data['device_id']
    logs = data['logs']
    
    conn = get_db_connection()
    device = conn.execute('SELECT logging_enabled FROM devices WHERE device_id = ?', (device_id,)).fetchone()
    if device and not device['logging_enabled']:
        conn.close()
        return jsonify({'status': 'ignored', 'reason': 'Logging disabled for this device'}), 200

    insert_data = []
    for log in logs:
        insert_data.append((
            device_id,
            log.get('timestamp', datetime.now().isoformat()),
            log.get('app_version', 'unknown'),
            log.get('level', 'INFO'),
            log.get('message', ''),
            log.get('stacktrace', '')
        ))

    conn.executemany('''
        INSERT INTO logs (device_id, timestamp, app_version, level, message, stacktrace)
        VALUES (?, ?, ?, ?, ?, ?)
    ''', insert_data)
    
    conn.execute('''
        INSERT INTO devices (device_id, logging_enabled, last_seen) 
        VALUES (?, 0, CURRENT_TIMESTAMP)
        ON CONFLICT(device_id) DO UPDATE SET last_seen=CURRENT_TIMESTAMP
    ''', (device_id,))
    
    conn.commit()
    conn.close()
    
    maybe_cleanup_old_logs()
    return jsonify({'status': 'success', 'inserted': len(insert_data)}), 200

@app.route('/api/devices/<device_id>/toggle', methods=['POST'])
def toggle_device(device_id):
    data = request.json or {}
    state = data.get('state')
    
    conn = get_db_connection()
    device = conn.execute('SELECT logging_enabled FROM devices WHERE device_id = ?', (device_id,)).fetchone()
    if not device:
        conn.close()
        return jsonify({'error': 'Device not found'}), 404
        
    new_status = int(state) if state is not None else (0 if device['logging_enabled'] else 1)
    conn.execute('UPDATE devices SET logging_enabled = ? WHERE device_id = ?', (new_status, device_id))
    conn.commit()
    conn.close()
    return jsonify({'status': 'success', 'logging_enabled': bool(new_status)}), 200

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
