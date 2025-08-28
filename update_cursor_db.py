import sqlite3
import os
import shutil

# Your MercyHacks keys
devDeviceId = "d2afe851-ddd5-449d-8057-256310261dbe"
machineId = "0f2c88c88c3bdb19ffd0080c480ffcc32430bb92c914789f1bc95435aa15e57c"
macMachineId = "8eb164dafed818edcb267efe053bb0fb12928804e81a28cac61a3ce6b32181a4fb965d62572015cf418d890dcd86e9f502a95e00a06096c158f6c3c6a0624f46"
sqmId = "{CA7FC444-C014-418E-8630-309E4D0F87BA}"

# Locate database
home_dir = os.path.expanduser("~")
db_path = os.path.join(home_dir, "Library/Application Support/Cursor/User/globalStorage/state.vscdb")

if os.path.exists(db_path):
    # Create backup
    backup_path = db_path + ".backup"
    shutil.copy2(db_path, backup_path)
    print(f"Created backup at {backup_path}")

    # Update values
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    cursor.execute("UPDATE ItemTable SET value = ? WHERE key = 'telemetry.devDeviceId'", (devDeviceId,))
    cursor.execute("UPDATE ItemTable SET value = ? WHERE key = 'telemetry.machineId'", (machineId,))
    cursor.execute("UPDATE ItemTable SET value = ? WHERE key = 'telemetry.macMachineId'", (macMachineId,))
    cursor.execute("UPDATE ItemTable SET value = ? WHERE key = 'telemetry.sqmId'", (sqmId,))
    cursor.execute("UPDATE ItemTable SET value = ? WHERE key = 'storage.serviceMachineId'", (devDeviceId,))

    conn.commit()
    conn.close()
    print("✅ Successfully updated Cursor database")
else:
    print(f"❌ Database not found at {db_path}")
