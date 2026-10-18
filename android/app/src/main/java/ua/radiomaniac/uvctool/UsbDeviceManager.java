package ua.radiomaniac.uvctool;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class UsbDeviceManager {

    private static final String TAG = "UvcTool";
    private static final String ACTION_USB_PERMISSION = "ua.radiomaniac.uvctool.USB_PERMISSION";
    /** USB class 14 = video. A UVC camera has a VideoControl (sub 1) and VideoStreaming (sub 2) interface. */
    private static final int USB_CLASS_VIDEO = 14;

    public interface Listener {
        void onDeviceAttached(UsbDevice device);
        void onDeviceDetached(UsbDevice device);
        void onPermissionGranted(UsbDevice device);
        void onPermissionDenied();
    }

    private final Context context;
    private final UsbManager usbManager;
    private Listener listener;
    private boolean mPermissionPending = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    UsbDevice device = getParcelableDevice(intent);
                    if (device != null && isTarget(device)) {
                        Log.d(TAG, "USB attached: " + device.getDeviceName());
                        if (listener != null) listener.onDeviceAttached(device);
                    }
                    break;
                }
                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    UsbDevice device = getParcelableDevice(intent);
                    if (device != null && isTarget(device)) {
                        Log.d(TAG, "USB detached: " + device.getDeviceName());
                        if (listener != null) listener.onDeviceDetached(device);
                    }
                    break;
                }
            }
        }
    };

    // Separate exported receiver for USB permission result so MIUI's USB service can deliver it.
    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = getParcelableDevice(intent);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Log.d(TAG, "USB permission broadcast: device=" +
                    (device != null ? device.getDeviceName() : "null") +
                    " granted=" + granted);
            if (device == null) {
                Log.w(TAG, "USB permission broadcast: device is null, ignoring");
                return;
            }
            mPermissionPending = false;
            if (granted) {
                Log.d(TAG, "USB permission granted for " + device.getDeviceName());
                if (listener != null) listener.onPermissionGranted(device);
            } else {
                Log.w(TAG, "USB permission denied for " + device.getDeviceName());
                if (listener != null) listener.onPermissionDenied();
            }
        }
    };

    public UsbDeviceManager(Context context) {
        // Use the passed context directly (must be Activity) so usbManager.requestPermission()
        // can show its dialog. ApplicationContext causes silent auto-deny on Android 14 + MIUI.
        this.context = context;
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void register() {
        IntentFilter attachFilter = new IntentFilter();
        attachFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        attachFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        IntentFilter permFilter = new IntentFilter(ACTION_USB_PERMISSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, attachFilter, Context.RECEIVER_NOT_EXPORTED);
            // EXPORTED so MIUI's USB system service can deliver the permission result broadcast
            context.registerReceiver(permissionReceiver, permFilter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, attachFilter);
            context.registerReceiver(permissionReceiver, permFilter);
        }
    }

    public void unregister() {
        try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        try { context.unregisterReceiver(permissionReceiver); } catch (IllegalArgumentException ignored) {}
    }

    /** All connected UVC cameras (directly or behind a hub), stable order. */
    public List<UsbDevice> findDevices() {
        List<UsbDevice> out = new ArrayList<>();
        if (usbManager == null) return out;
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (isTarget(device)) out.add(device);
        }
        Collections.sort(out, (a, b) -> a.getDeviceName().compareTo(b.getDeviceName()));
        return out;
    }

    /**
     * The camera to open without asking: the only one connected, or the one last used
     * (by VID:PID) when several are. null if none, or several and none remembered.
     */
    public UsbDevice findDevice(String preferredKey) {
        List<UsbDevice> all = findDevices();
        if (all.isEmpty()) return null;
        if (all.size() == 1) return all.get(0);
        for (UsbDevice d : all) if (key(d).equals(preferredKey)) return d;
        return null;
    }

    public UsbDevice findDevice() { return findDevice(null); }

    /** "349c:0411" — what we remember a camera by. */
    public static String key(UsbDevice d) {
        return String.format(Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId());
    }

    /** "Generic HD camera · 349c:0411" */
    public static String label(UsbDevice d) {
        String name = null;
        try { name = d.getProductName(); } catch (Exception ignored) {}
        if (name == null || name.trim().isEmpty()) name = "USB camera";
        return name.trim() + " · " + key(d);
    }

    public boolean hasPermission(UsbDevice device) {
        boolean result = usbManager != null && device != null && usbManager.hasPermission(device);
        Log.d(TAG, "hasPermission: device=" + (device != null ? device.getDeviceName() : "null") + " → " + result);
        return result;
    }

    public UsbDevice getTargetDeviceFromIntent(android.content.Intent intent) {
        UsbDevice device = getParcelableDevice(intent);
        return (device != null && isTarget(device)) ? device : null;
    }

    public void requestPermission(UsbDevice device) {
        if (usbManager == null || device == null) return;
        if (mPermissionPending) {
            Log.d(TAG, "requestPermission: already pending, skip duplicate for " + device.getDeviceName());
            return;
        }
        mPermissionPending = true;
        Log.d(TAG, "requestPermission: " + device.getDeviceName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(context.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);
        usbManager.requestPermission(device, pi);
    }

    /** Any UVC camera: has a video-class interface. */
    private boolean isTarget(UsbDevice device) {
        if (device.getDeviceClass() == USB_CLASS_VIDEO) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == USB_CLASS_VIDEO) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private UsbDevice getParcelableDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        } else {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
    }
}
