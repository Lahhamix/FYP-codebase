import numpy as np
import pandas as pd

# Load the CSV file
csv_file = r'C:\Users\georg\OneDrive - American University of Beirut\Uni\(11) SPRNG 25-26\EECE 502\Peak Detection\MAX30102_by_RF-master\filtered_ppg_data_Transpose.csv'  # Change this to your actual file path

# Read the CSV file using pandas
data = pd.read_csv(csv_file)

# Check the first few rows of the data (for debugging)
print(data.head())

# Convert the DataFrame to a NumPy array
npy_data = data.to_numpy()

# Save the NumPy array as a .npy file
np.save('filtered_ppg_data_Transpose.npy', npy_data)

# Verify by loading the saved .npy file
loaded_data = np.load('filtered_ppg_data.npy')
print(loaded_data)