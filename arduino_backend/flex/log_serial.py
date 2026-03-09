import serial
import time

PORT = "COM10"     
BAUD = 115200
OUTFILE = "one_run.csv"

ser = serial.Serial(PORT, BAUD, timeout=1)
time.sleep(2)

with open(OUTFILE, "w", newline="") as f:
    started = False
    print("Waiting for CSV header...")

    while True:
        line = ser.readline().decode(errors="ignore").strip()
        if not line:
            continue

        # Start logging only when header appears
        if not started:
            if line.startswith("time_s,raw1,raw2"):
                started = True
                f.write(line + "\n")
                print("Logging started ->", OUTFILE)
            continue

        # Save only numeric CSV rows (data rows start with a digit)
        if line[0].isdigit():
            f.write(line + "\n")

        # Show live output too
        print(line)