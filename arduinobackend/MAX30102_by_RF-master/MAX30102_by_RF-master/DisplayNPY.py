import numpy as np
import matplotlib.pyplot as plt

# load one of these files. First one is the original NOY from arduino CSV (incorrect format)
# data = np.load("filtered_ppg_data.npy")
data = np.load("filtered_ppg_data_Transpose.npy")

print("Shape:", data.shape)   

# display one segment
plt.figure(figsize=(12,4))
plt.plot(data[0])   # first signal, in this case its teh RED signal (More noisey)
plt.title("PPG signal - segment 0")
plt.xlabel("Sample")
plt.ylabel("Amplitude")
plt.grid(True)
plt.show()