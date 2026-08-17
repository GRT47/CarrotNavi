import os
import sqlite3
from datetime import datetime
from flask import Flask, request, jsonify, render_template

app = Flask(__name__)
app.config['TEMPLATES_AUTO_RELOAD'] = True

if not os.path.exists('data'):
    os.makedirs('data')
DB_FILE = 'data/logs.db'

def get_db_connection():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db_connection()
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
    
    # Add alias column if it doesn't exist (migration)
    try:
        conn.execute('ALTER TABLE devices ADD COLUMN alias TEXT')
    except sqlite3.OperationalError:
        pass # Column already exists
        
    conn.commit()
    conn.close()

init_db()

@app.route('/')
def index():
    conn = get_db_connection()
    devices = conn.execute('''
        SELECT *, 
        (julianday('now') - julianday(last_seen)) * 86400 AS seconds_since_last_seen 
        FROM devices ORDER BY last_seen DESC
    ''').fetchall()
    conn.close()
    return render_template('index.html', devices=devices)

@app.route('/device/<device_id>')
def device_logs(device_id):
    page = request.args.get('page', 1, type=int)
    start_date = request.args.get('start_date')
    end_date = request.args.get('end_date')
    
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
        
    query += ' ORDER BY created_at DESC LIMIT ? OFFSET ?'
    
    conn = get_db_connection()
    total_count = conn.execute(count_query, params).fetchone()[0]
    total_pages = (total_count + per_page - 1) // per_page if total_count > 0 else 1
    
    logs = conn.execute(query, params + [per_page, offset]).fetchall()
    device = conn.execute('SELECT * FROM devices WHERE device_id = ?', (device_id,)).fetchone()
    conn.close()
    
    device_alias = device['alias'] if device and device['alias'] else device_id
    
    return render_template('device_logs.html', logs=logs, selected_device=device_id, device_alias=device_alias, page=page, total_pages=total_pages, start_date=start_date, end_date=end_date)

@app.route('/device/<device_id>/download')
def download_logs(device_id):
    start_date = request.args.get('start_date')
    end_date = request.args.get('end_date')
    
    query = 'SELECT * FROM logs WHERE device_id = ?'
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
            
    query += ' ORDER BY created_at ASC'
    
    conn = get_db_connection()
    logs = conn.execute(query, params).fetchall()
    conn.close()
    
    def generate():
        for log in logs:
            line = f"[{log['timestamp']}] {log['level']} : {log['message']}"
            if log['stacktrace']:
                line += f"\n{log['stacktrace']}"
            yield line + '\n'
            
    from flask import Response
    filename = f"logs_{device_id}.txt"
    if start_date or end_date:
        filename = f"logs_{device_id}_filtered.txt"
        
    return Response(generate(), mimetype='text/plain', headers={"Content-Disposition": f"attachment;filename={filename}"})

@app.route('/api/config', methods=['GET'])
def get_config():
    device_id = request.args.get('device_id')
    if not device_id:
        return jsonify({'error': 'device_id required'}), 400
        
    conn = get_db_connection()
    device = conn.execute('SELECT logging_enabled FROM devices WHERE device_id = ?', (device_id,)).fetchone()
    
    if not device:
        # Register new device with logging disabled by default
        conn.execute('INSERT INTO devices (device_id, logging_enabled) VALUES (?, 0)', (device_id,))
        conn.commit()
        logging_enabled = 0
    else:
        # Update last seen
        conn.execute('UPDATE devices SET last_seen = CURRENT_TIMESTAMP WHERE device_id = ?', (device_id,))
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
    
    # 7일 경과 로그 삭제
    conn.execute("DELETE FROM logs WHERE created_at < datetime('now', '-7 days')")
    
    conn.commit()
    conn.close()
    return jsonify({'status': 'success', 'inserted': len(insert_data)}), 200

@app.route('/api/devices/<device_id>/toggle', methods=['POST'])
def toggle_device(device_id):
    conn = get_db_connection()
    device = conn.execute('SELECT logging_enabled FROM devices WHERE device_id = ?', (device_id,)).fetchone()
    if not device:
        conn.close()
        return jsonify({'error': 'Device not found'}), 404
        
    new_status = 0 if device['logging_enabled'] else 1
    conn.execute('UPDATE devices SET logging_enabled = ? WHERE device_id = ?', (new_status, device_id))
    conn.commit()
    conn.close()
    return jsonify({'status': 'success', 'logging_enabled': bool(new_status)}), 200

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
