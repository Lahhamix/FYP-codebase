import serial
import csv

# Set up the serial connection (replace with your Arduino's COM port)
ser = serial.Serial('COM10', 115200)  # Example for Windows (COM port), replace with the correct port for your system

# Open a CSV file to save data
with open('filtered_ppg_data.csv', 'w', newline='') as csvfile:
    fieldnames = ['Red Signal', 'IR Signal']
    writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
    writer.writeheader()

    # Read data from the Arduino serial port and save it to the CSV
    while True:
        line = ser.readline().decode('utf-8').strip()  # Read a line of data from Arduino
        if line:
            # Split the received data (red, IR signal)
            red_signal, ir_signal = line.split(',')
            # Write the data to CSV file
            writer.writerow({'Red Signal': red_signal, 'IR Signal': ir_signal})
            print(f'Red: {red_signal}, IR: {ir_signal}')  # Optionally print data to console