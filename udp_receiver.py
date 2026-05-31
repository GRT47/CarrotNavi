import socket

UDP_IP = "0.0.0.0"
UDP_PORT = 7706

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

# Windows에서는 SO_BROADCAST 옵션이 필요할 수 있습니다.
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
sock.bind((UDP_IP, UDP_PORT))

print(f"Listening for UDP packets on port {UDP_PORT}...")

while True:
    data, addr = sock.recvfrom(4096)
    print(f"\n--- Received from {addr} ---")
    try:
        decoded_data = data.decode('utf-8')
        print(decoded_data)
    except Exception as e:
        print(f"Raw data: {data}")
