import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.colors import ListedColormap

df = pd.read_csv("one_run.csv").dropna(subset=["time_s", "total_dev", "label"])

# Time starting from 0 so it's easier to read
t = df["time_s"] - df["time_s"].iloc[0]

# Ensure labels are in a consistent order
label_order = ["none", "subclinical", "mild", "moderate", "severe"]
df["label"] = pd.Categorical(df["label"], categories=label_order, ordered=True)

# --- Custom colors (change subclinical away from orange here) ---
label_colors = {
    "none": "tab:blue",
    "subclinical": "tab:cyan",   # <-- changed from orange
    "mild": "tab:green",
    "moderate": "tab:red",
    "severe": "tab:purple",
}

# Colormap for scatter points (must follow label_order)
cmap = ListedColormap([label_colors[l] for l in label_order])

fig, ax = plt.subplots(figsize=(10, 5))

# ---- Severity bands (shaded) ----
ymax = max(df["total_dev"].max() * 1.05, 25)  # ensure severe band is visible
ax.set_ylim(0, ymax)

bands = [
    ("none (0–3)",         0,   3,  label_colors["none"]),
    ("subclinical (3–5)",  3,   5,  label_colors["subclinical"]),
    ("mild (5–10)",        5,  10,  label_colors["mild"]),
    ("moderate (10–20)",  10,  20,  label_colors["moderate"]),
    ("severe (20+)",      20, ymax, label_colors["severe"]),
]

band_handles = []
for name, y0, y1, c in bands:
    ax.axhspan(y0, y1, color=c, alpha=0.08, zorder=0)
    band_handles.append(Patch(facecolor=c, alpha=0.12, label=name))

# ---- Main deviation curve ----
ax.plot(t, df["total_dev"], linewidth=2, label="Total deviation")

# ---- Threshold lines (colored to match their band) ----
thresholds = [
    ("Subclinical threshold (3)", 3,  label_colors["subclinical"]),
    ("Mild threshold (5)",        5,  label_colors["mild"]),
    ("Moderate threshold (10)",   10, label_colors["moderate"]),
    ("Severe threshold (20)",     20, label_colors["severe"]),
]
for name, val, col in thresholds:
    ax.axhline(val, linestyle="--", linewidth=1, color=col, label=name)

# ---- Colored markers for the classifier output (predicted label column) ----
codes = df["label"].cat.codes
ax.scatter(t, df["total_dev"], c=codes, cmap=cmap, s=18, alpha=0.9)

# ---- Vertical markers where the predicted label changes ----
change_mask = df["label"].ne(df["label"].shift())
for ct in t[change_mask]:
    ax.axvline(ct, linestyle=":", linewidth=0.8, alpha=0.6)

# ---- Legends ----
# Legend 1: curve + threshold lines
leg1 = ax.legend(loc="upper left", frameon=True)
ax.add_artist(leg1)

# Legend 2: predicted label colors
label_handles = [
    Line2D([0], [0], marker='o', color='w', label=lab,
           markerfacecolor=label_colors[lab], markersize=7)
    for lab in label_order
]
leg2 = ax.legend(handles=label_handles, title="Predicted label",
                 loc="upper left", bbox_to_anchor=(0, 0.55), frameon=True)
ax.add_artist(leg2)

# Legend 3: severity bands (MOVED LEFT so it doesn't hide the curve)
ax.legend(handles=band_handles, title="Severity bands",
          loc="upper right", bbox_to_anchor=(0.78, 1.0), frameon=True)

# ---- Labels ----
ax.set_xlabel("Time since logging started (s)")
ax.set_ylabel("Total deviation (ADC counts)")
ax.set_title("Deviation profile while changing tightness (bands + predicted labels)")
ax.grid(True, alpha=0.25)

plt.tight_layout()
plt.savefig("fig_one_run_timeseries_annotated_bands_customcolors.png", dpi=300)
plt.show()

print("Saved: fig_one_run_timeseries_annotated_bands_customcolors.png")