package com.example.bloodpressuremonitoring;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    // --- Phase 1: BT + permissions ---
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS_CODE = 2;

    // --- Phase 2: Scan ---
    private static final long SCAN_PERIOD = 10000; // 10s

    // --- Blood Pressure UUIDs ---
    private static final UUID BPS_SERVICE_UUID =
            UUID.fromString("00001810-0000-1000-8000-00805f9b34fb"); // 0x1810
    private static final UUID BPS_MEASUREMENT_UUID =
            UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb"); // 0x2A35
    private static final UUID BPS_FEATURE_UUID =
            UUID.fromString("00002a49-0000-1000-8000-00805f9b34fb"); // 0x2A49
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"); // 0x2902
    // RACP + BP Record
    private static final UUID RACP_UUID =
            UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb"); // Record Access Control Point

    private static final UUID BP_RECORD_UUID =
            UUID.fromString("00002b36-0000-1000-8000-00805f9b34fb"); // Blood Pressure Record (Enhanced BLS)

    private BluetoothGattCharacteristic racpChar;
    private BluetoothGattCharacteristic bpRecordChar;

    // --- Record flow state ---
    private enum RecordSetupStep { NONE, ENABLE_RACP_CCCD, ENABLE_BP_RECORD_CCCD, SEND_RACP_CMD, DONE }
    private RecordSetupStep recordStep = RecordSetupStep.NONE;



    // UI
    private TextView txtFeature;

    private Button btnEnableScan;
    private TextView txtStatus;

    private TextView txtBp;
    private TextView txtPulse;
    private TextView txtTime;

    private ListView devicesListView;

    // BLE
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean scanning = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ArrayList<String> deviceList = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;
    private final ArrayList<BluetoothDevice> discoveredDevices = new ArrayList<>();

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic measurementChar;
    private BluetoothGattCharacteristic featureChar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnEnableScan = findViewById(R.id.btn_enable_scan);
        txtStatus = findViewById(R.id.txt_status);

        txtBp = findViewById(R.id.txt_bp);
        txtPulse = findViewById(R.id.txt_pulse);
        txtTime = findViewById(R.id.txt_time);
        txtFeature = findViewById(R.id.txt_feature);
        txtFeature.setText("Features : --");


        devicesListView = findViewById(R.id.devices_list);

        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        devicesListView.setAdapter(listAdapter);

        // Init status log
        txtStatus.setText("Status log:");

        // Init measure UI
        txtBp.setText("Tension : -- / -- mmHg");
        txtPulse.setText("Pouls : -- bpm");
        txtTime.setText("Date : --");

        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = (bluetoothManager != null) ? bluetoothManager.getAdapter() : null;

        btnEnableScan.setOnClickListener(v -> {
            logStatus("BTN: enable BT + scan");
            activateBluetooth();
        });

        devicesListView.setOnItemClickListener((parent, view, position, id) -> {
            if (scanning) {
                logStatus("Stop scan (user selected device)");
                scanLeDevice(false);
            }
            BluetoothDevice device = discoveredDevices.get(position);
            logStatus("User selected: " + safeName(device) + " / " + device.getAddress());
            connectToDevice(device);
        });

        logStatus("App started");
    }

    // =========================
    // Permanent log (append)
    // =========================
    private void logStatus(String s) {
        runOnUiThread(() -> {
            String old = txtStatus.getText().toString();
            txtStatus.setText(old + "\n- " + s);
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // =========================
    // Phase 1: BT + permissions
    // =========================
    private void activateBluetooth() {
        if (!checkAndRequestPermissions()) {
            logStatus("Permissions: request sent");
            return;
        }
        logStatus("Permissions: OK");

        if (bluetoothAdapter == null) {
            toast("Bluetooth non supporté");
            logStatus("Bluetooth adapter: NULL (not supported)");
            return;
        }
        logStatus("Bluetooth adapter: OK");

        if (!bluetoothAdapter.isEnabled()) {
            logStatus("Bluetooth: disabled -> request enable");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    logStatus("Missing BLUETOOTH_CONNECT permission (cannot request enable)");
                    return;
                }
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            logStatus("Bluetooth: already enabled -> start scan");
            scanLeDevice(true);
        }
    }

    private boolean checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN);

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT);

            // Some devices/ROMs still require location granted for scan results
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!needed.isEmpty()) {
            logStatus("Permissions missing: " + needed);
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                logStatus("Permissions result: ALL GRANTED");
                activateBluetooth();
            } else {
                logStatus("Permissions result: DENIED");
                toast("Permissions nécessaires pour BLE");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                logStatus("Bluetooth enable result: OK");
                toast("Bluetooth activé");
                scanLeDevice(true);
            } else {
                logStatus("Bluetooth enable result: CANCELLED");
                toast("Activation Bluetooth annulée");
            }
        }
    }

    // =========================
    // Phase 2: Scan BLE
    // =========================
    private void scanLeDevice(final boolean enable) {
        if (!checkAndRequestPermissions()) {
            logStatus("Scan: missing permissions");
            return;
        }

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }
        if (bluetoothLeScanner == null) {
            logStatus("BLE scanner: NULL (unavailable)");
            return;
        }
        logStatus("BLE scanner: OK");

        if (enable) {
            handler.postDelayed(() -> {
                scanning = false;
                stopScanSafe();
                logStatus("Scan: timeout reached -> stop");
            }, SCAN_PERIOD);

            scanning = true;
            discoveredDevices.clear();
            listAdapter.clear();
            logStatus("Scan: START (" + (SCAN_PERIOD / 1000) + "s)");

            startScanSafe();
        } else {
            scanning = false;
            stopScanSafe();
            logStatus("Scan: STOP (manual)");
        }
    }

    private void startScanSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                logStatus("startScan: missing BLUETOOTH_SCAN");
                return;
            }
        }
        bluetoothLeScanner.startScan(leScanCallback);
    }

    private void stopScanSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                logStatus("stopScan: missing BLUETOOTH_SCAN");
                return;
            }
        }
        bluetoothLeScanner.stopScan(leScanCallback);
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    logStatus("ScanResult ignored: missing BLUETOOTH_CONNECT");
                    return;
                }
            }

            String name = device.getName();
            if (name == null) name = "(no name)";

            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device);
                listAdapter.add(name + "\n" + device.getAddress());
                listAdapter.notifyDataSetChanged();
            }
        }
    };

    // =========================
    // Phase 3: Connect GATT
    // =========================
    private void connectToDevice(BluetoothDevice device) {
        closeGatt();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                logStatus("connectGatt: missing BLUETOOTH_CONNECT");
                return;
            }
        }

        logStatus("GATT: connectGatt() called");
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            logStatus("GATT: onConnectionStateChange status=" + status + " newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                logStatus("GATT: CONNECTED -> discoverServices()");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        logStatus("discoverServices blocked: missing BLUETOOTH_CONNECT");
                        return;
                    }
                }
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logStatus("GATT: DISCONNECTED");
                runOnUiThread(() -> {
                    txtBp.setText("Tension : -- / --");
                    txtPulse.setText("Pouls : -- bpm");
                    txtTime.setText("Date : --");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            logStatus("GATT: onServicesDiscovered status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logStatus("discoverServices FAILED");
                return;
            }

            BluetoothGattService bps = gatt.getService(BPS_SERVICE_UUID);
            logStatus("Service BPS (0x1810): " + (bps != null ? "FOUND" : "NOT FOUND"));
            if (bps == null) return;
            racpChar = bps.getCharacteristic(RACP_UUID);
            bpRecordChar = bps.getCharacteristic(BP_RECORD_UUID);

            logStatus("Char RACP (0x2A52): " + (racpChar != null ? "FOUND" : "NOT FOUND"));
            logStatus("Char BP Record (0x2B36): " + (bpRecordChar != null ? "FOUND" : "NOT FOUND"));

            measurementChar = bps.getCharacteristic(BPS_MEASUREMENT_UUID);
            featureChar = bps.getCharacteristic(BPS_FEATURE_UUID);

            logStatus("Char Measurement (0x2A35): " + (measurementChar != null ? "FOUND" : "NOT FOUND"));
            logStatus("Char Feature (0x2A49): " + (featureChar != null ? "FOUND" : "NOT FOUND"));

            // Read Feature (optional)
            if (featureChar != null && (featureChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                logStatus("Feature: READ requested");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        logStatus("readCharacteristic blocked: missing BLUETOOTH_CONNECT");
                        return;
                    }
                }
                boolean ok = gatt.readCharacteristic(featureChar);
                logStatus("Feature: readCharacteristic() returned " + ok);
            } else {
                logStatus("Feature: not readable or missing");
                // even if feature not readable, subscribe anyway
                subscribeMeasurement(gatt);
                // On démarre la config Record (RACP + BP Record) dès qu'on a le service


            }
        }
        private void subscribeRecordStuff(BluetoothGatt gatt) {
            if (recordStep != RecordSetupStep.NONE) {
                logStatus("Record flow already started: " + recordStep);
                return;
            }

            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return;

            if (racpChar == null) {
                logStatus("RACP: missing (0x2A52) -> record flow disabled");
                return;
            }
            if (bpRecordChar == null) {
                logStatus("BP Record: missing (0x2B36) -> record flow disabled");
                return;
            }

            logStatus("Record flow: START");

            // Step 1: enable indications on RACP (CCCD = 02 00)
            recordStep = RecordSetupStep.ENABLE_RACP_CCCD;

            boolean ok = gatt.setCharacteristicNotification(racpChar, true);
            logStatus("RACP: setCharacteristicNotification(true) -> " + ok);

            BluetoothGattDescriptor cccd = racpChar.getDescriptor(CCCD_UUID);
            logStatus("RACP CCCD (0x2902): " + (cccd != null ? "FOUND" : "NOT FOUND"));
            if (cccd == null) return;

            cccd.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE); // 02 00
            boolean w = gatt.writeDescriptor(cccd);
            logStatus("RACP: write CCCD (INDICATE) -> " + w);
        }



        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            logStatus("GATT: onCharacteristicRead uuid=" + characteristic.getUuid() + " status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                subscribeMeasurement(gatt);
                return;
            }

            if (BPS_FEATURE_UUID.equals(characteristic.getUuid())) {
                byte[] v = characteristic.getValue();
                logStatus("Feature raw: " + bytesToHex(v));

                String decoded = decodeBpsFeature(v);
                logStatus("Feature decoded: " + decoded);

                runOnUiThread(() -> txtFeature.setText("Features : " + decoded));

                // ensuite tu t'abonnes à la measurement
                subscribeMeasurement(gatt);


            }
        }


        private void subscribeMeasurement(BluetoothGatt gatt) {
            if (measurementChar == null) {
                logStatus("Measurement: missing, cannot subscribe");
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    logStatus("subscribe blocked: missing BLUETOOTH_CONNECT");
                    return;
                }
            }

            boolean notifOk = gatt.setCharacteristicNotification(measurementChar, true);
            logStatus("Measurement: setCharacteristicNotification(true) -> " + notifOk);

            BluetoothGattDescriptor cccd = measurementChar.getDescriptor(CCCD_UUID);
            logStatus("Descriptor CCCD (0x2902): " + (cccd != null ? "FOUND" : "NOT FOUND"));
            if (cccd == null) return;

            // Most BP devices use INDICATE.
            cccd.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE); // 02 00
            boolean writeOk = gatt.writeDescriptor(cccd);
            logStatus("CCCD: writeDescriptor(ENABLE_INDICATION) -> " + writeOk);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            logStatus("GATT: onDescriptorWrite descUuid=" + descriptor.getUuid()
                    + " charUuid=" + descriptor.getCharacteristic().getUuid()
                    + " status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                logStatus("Descriptor write FAILED");
                return;
            }

            // We only care about CCCD writes
            if (!CCCD_UUID.equals(descriptor.getUuid())) {
                return;
            }

            UUID charUuid = descriptor.getCharacteristic().getUuid();
            byte[] v = descriptor.getValue();
            logStatus("CCCD value written: " + bytesToHex(v));

            // =========================================================
            // (A) If Measurement CCCD is done, start Record flow
            // =========================================================
            if (BPS_MEASUREMENT_UUID.equals(charUuid)) {
                if (recordStep == RecordSetupStep.NONE) {
                    logStatus("Measurement CCCD done -> start Record flow (delayed)");
                    handler.postDelayed(() -> subscribeRecordStuff(gatt), 200);
                } else {
                    logStatus("Measurement CCCD done -> recordStep already " + recordStep);
                }
                return;
            }


            // =========================================================
            // (B) Record flow chaining
            // =========================================================

            // Step 1 done: RACP CCCD enabled -> enable BP Record CCCD
            if (RACP_UUID.equals(charUuid) && recordStep == RecordSetupStep.ENABLE_RACP_CCCD) {
                logStatus("Record flow: RACP CCCD enabled -> enable BP Record CCCD");
                recordStep = RecordSetupStep.ENABLE_BP_RECORD_CCCD;

                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    logStatus("Missing BLUETOOTH_CONNECT (cannot enable BP Record)");
                    return;
                }

                if (bpRecordChar == null) {
                    logStatus("BP Record char is NULL");
                    return;
                }

                boolean ok = gatt.setCharacteristicNotification(bpRecordChar, true);
                logStatus("BP Record: setCharacteristicNotification(true) -> " + ok);

                BluetoothGattDescriptor cccd = bpRecordChar.getDescriptor(CCCD_UUID);
                logStatus("BP Record CCCD (0x2902): " + (cccd != null ? "FOUND" : "NOT FOUND"));
                if (cccd == null) return;

                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); // 01 00
                boolean w = gatt.writeDescriptor(cccd);
                logStatus("BP Record: write CCCD (NOTIFY) -> " + w);
                return;
            }

            // Step 2 done: BP Record CCCD enabled -> send RACP command 01 01
            if (BP_RECORD_UUID.equals(charUuid) && recordStep == RecordSetupStep.ENABLE_BP_RECORD_CCCD) {
                logStatus("Record flow: BP Record CCCD enabled -> send RACP cmd (01 01)");
                recordStep = RecordSetupStep.SEND_RACP_CMD;

                requestAllRecords(gatt);

                recordStep = RecordSetupStep.DONE;
                logStatus("Record flow: DONE");
                return;
            }

            // If we reach here, it's a CCCD write we didn't expect in the current step
            logStatus("CCCD write ignored (char=" + charUuid + ", recordStep=" + recordStep + ")");
        }


        private void requestAllRecords(BluetoothGatt gatt) {
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return;

            if (racpChar == null) {
                logStatus("RACP: missing, cannot request records");
                return;
            }

            // RACP: Report Stored Records (0x01), Operator: All records (0x01)
            byte[] cmd = new byte[]{0x01, 0x01};
            racpChar.setValue(cmd);

            boolean ok = gatt.writeCharacteristic(racpChar);
            logStatus("RACP: write cmd '01 01' -> " + ok);
        }



        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            UUID uuid = characteristic.getUuid();
            byte[] data = characteristic.getValue();

            logStatus("GATT: onCharacteristicChanged uuid=" + uuid);

            // =========================
            // (1) RACP (0x2A52) - Indication response
            // =========================
            if (RACP_UUID.equals(uuid)) {
                logStatus("RACP INDICATION len=" + (data == null ? -1 : data.length));
                logStatus("RACP INDICATION raw: " + bytesToHex(data));
                // Optionnel: tu peux décoder plus tard (opcode response / number of records / etc.)
                return;
            }

            // =========================
            // (2) Blood Pressure Record (0x2B36) - Notify
            // =========================
            if (BP_RECORD_UUID.equals(uuid)) {
                logStatus("BP RECORD NOTIFY len=" + (data == null ? -1 : data.length));
                logStatus("BP RECORD NOTIFY raw: " + bytesToHex(data));
                logStatus("BP RECORD header: " + parseBpRecordHeader(data));

                // Optionnel: si le record transporte un BP Measurement (UUID 0x2A35),
                // tu peux tenter de parser le payload ici plus tard.
                return;
            }

            // =========================
            // (3) Blood Pressure Measurement (0x2A35) - Indicate/Notify
            // =========================
            if (!BPS_MEASUREMENT_UUID.equals(uuid)) {
                logStatus("Changed ignored: not Measurement/RACP/Record");
                return;
            }

            // RAW log
            logStatus("Measurement len=" + (data == null ? -1 : data.length));
            logStatus("Measurement raw: " + bytesToHex(data));

            // Parse measurement
            ParsedBpsMeasurement parsed = parseBpsMeasurement(data);
            logStatus("Measurement parsed: " + (parsed == null ? "NULL" :
                    String.format(Locale.US,
                            "SYS=%.2f DIA=%.2f MAP=%.2f %s ts=%s pulse=%s user=%s status=%s",
                            parsed.systolic, parsed.diastolic, parsed.map, parsed.unit,
                            String.valueOf(parsed.timestamp),
                            String.valueOf(parsed.pulseRate),
                            String.valueOf(parsed.userId),
                            String.valueOf(parsed.status)
                    )));

            // UI update
            runOnUiThread(() -> {
                if (parsed == null) {
                    txtBp.setText("Tension : -- / --");
                    txtPulse.setText("Pouls : -- bpm");
                    txtTime.setText("Date : --");
                    return;
                }

                txtBp.setText(String.format(
                        Locale.US,
                        "Tension : %.0f / %.0f %s",
                        parsed.systolic,
                        parsed.diastolic,
                        parsed.unit
                ));

                if (parsed.pulseRate != null) {
                    txtPulse.setText(String.format(Locale.US, "Pouls : %.0f bpm", parsed.pulseRate));
                } else {
                    txtPulse.setText("Pouls : -- bpm");
                }

                if (parsed.timestamp != null) {
                    txtTime.setText("Date : " + parsed.timestamp);
                } else {
                    txtTime.setText("Date : --");
                }
            });
        }

    };

    // =========================
    // Cleanup
    // =========================
    private void closeGatt() {
        if (bluetoothGatt != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    logStatus("closeGatt blocked: missing BLUETOOTH_CONNECT");
                    return;
                }
            }
            logStatus("GATT: close()");
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        measurementChar = null;
        featureChar = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeGatt();
    }

    private String safeName(BluetoothDevice d) {
        if (d == null) return "(null)";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return "(no permission)";
        }
        String n = d.getName();
        return (n != null) ? n : "(no name)";
    }

    // =========================
    // Helpers
    // =========================
    private static String decodeBpsFeature(byte[] v) {
        if (v == null || v.length < 2) return "(invalid feature length)";

        int feature = (v[0] & 0xFF) | ((v[1] & 0xFF) << 8);

        List<String> caps = new ArrayList<>();

        // Bits selon la spec Blood Pressure Feature (0x2A49)
        if ((feature & 0x0001) != 0) caps.add("Body Movement Detection");
        if ((feature & 0x0002) != 0) caps.add("Cuff Fit Detection");
        if ((feature & 0x0004) != 0) caps.add("Irregular Pulse Detection");
        if ((feature & 0x0008) != 0) caps.add("Pulse Rate Range Detection");
        if ((feature & 0x0010) != 0) caps.add("Measurement Position Detection");
        if ((feature & 0x0020) != 0) caps.add("Multiple Bond");

        if (caps.isEmpty()) return String.format(Locale.US, "0x%04X (no flags)", feature);

        // Format compact
        return String.format(Locale.US, "0x%04X (%s)", feature, joinWithComma(caps));
    }

    private static String joinWithComma(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }
    private static String parseBpRecordHeader(byte[] d) {
        if (d == null) return "(null)";
        if (d.length < 5) return "(too short: need >=5)";

        int seg = d[0] & 0xFF;
        boolean first = (seg & 0x80) != 0;
        boolean last  = (seg & 0x40) != 0;
        int counter   = (seg & 0x3F);

        int seq = (d[1] & 0xFF) | ((d[2] & 0xFF) << 8);
        int uuid16 = (d[3] & 0xFF) | ((d[4] & 0xFF) << 8);

        int payloadLen = d.length - 5;

        return String.format(Locale.US,
                "seg[first=%s last=%s ctr=%d] seq=%d uuid=0x%04X payloadLen=%d",
                first, last, counter, seq, uuid16, payloadLen);
    }


    private static String bytesToHex(byte[] data) {
        if (data == null) return "(null)";
        if (data.length == 0) return "(empty)";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) sb.append(String.format(Locale.US, "%02X ", b));
        return sb.toString().trim();
    }

    // Complete parsing of 0x2A35 based on flags
    private static ParsedBpsMeasurement parseBpsMeasurement(byte[] data) {
        try {
            if (data == null || data.length < 1 + 6) return null;

            int flags = data[0] & 0xFF;
            boolean unitKpa = (flags & 0x01) != 0;
            boolean hasTimestamp = (flags & 0x02) != 0;
            boolean hasPulse = (flags & 0x04) != 0;
            boolean hasUserId = (flags & 0x08) != 0;
            boolean hasStatus = (flags & 0x10) != 0;

            int idx = 1;

            float systolic = sfloatToFloat(data[idx], data[idx + 1]); idx += 2;
            float diastolic = sfloatToFloat(data[idx], data[idx + 1]); idx += 2;
            float map = sfloatToFloat(data[idx], data[idx + 1]); idx += 2;

            ParsedBpsMeasurement p = new ParsedBpsMeasurement();
            p.systolic = systolic;
            p.diastolic = diastolic;
            p.map = map;
            p.unit = unitKpa ? "kPa" : "mmHg";

            if (hasTimestamp) {
                if (idx + 7 > data.length) return null;

                int year = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8); idx += 2;
                int month = data[idx++] & 0xFF;
                int day = data[idx++] & 0xFF;
                int hour = data[idx++] & 0xFF;
                int minute = data[idx++] & 0xFF;
                int second = data[idx++] & 0xFF;

                p.timestamp = String.format(Locale.US,
                        "%04d-%02d-%02d %02d:%02d:%02d",
                        year, month, day, hour, minute, second);
            }

            if (hasPulse) {
                if (idx + 2 > data.length) return null;
                p.pulseRate = sfloatToFloat(data[idx], data[idx + 1]);
                idx += 2;
            }

            if (hasUserId) {
                if (idx + 1 > data.length) return null;
                p.userId = data[idx++] & 0xFF;
            }

            if (hasStatus) {
                if (idx + 2 > data.length) return null;
                p.status = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8);
                idx += 2;
            }

            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // IEEE-11073 SFLOAT (16-bit): 12-bit mantissa + 4-bit exponent base10
    private static float sfloatToFloat(byte b0, byte b1) {
        int raw = ((b1 & 0xFF) << 8) | (b0 & 0xFF);

        int mantissa = raw & 0x0FFF;
        int exponent = (raw >> 12) & 0x000F;

        // sign extend mantissa (12-bit signed)
        if ((mantissa & 0x0800) != 0) mantissa |= 0xFFFFF000;
        // sign extend exponent (4-bit signed)
        if ((exponent & 0x08) != 0) exponent |= 0xFFFFFFF0;

        return (float) (mantissa * Math.pow(10, exponent));
    }

    private static class ParsedBpsMeasurement {
        float systolic;
        float diastolic;
        float map;
        String unit;

        String timestamp;   // optional
        Float pulseRate;    // optional
        Integer userId;     // optional
        Integer status;     // optional
    }
}
