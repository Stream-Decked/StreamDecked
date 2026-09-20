package dev.wolfieboy09.streamdecked.core.hid;

import com.mojang.logging.LogUtils;
import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.hid4java.HidServicesSpecification;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Default backend, built on Hid4Java (JNA + bundled hidapi natives for Windows, macOS and Linux).
 *
 * <p>Hid4Java's own device-scanning thread is switched off here; the driver polls on its own
 * schedule, so a second thread would only add races.</p>
 *
 * <p><b>Linux:</b> without an udev rule the OS hands out neither the serial number nor write
 * access. See the README for the rule to install.</p>
 */
public final class Hid4JavaBackend implements HidBackend {
    public static final Logger LOGGER = LogUtils.getLogger();

    private HidServices services;

    private synchronized HidServices services() {
        if (services == null) {
            HidServicesSpecification spec = new HidServicesSpecification();
            spec.setAutoStart(false);
            spec.setAutoShutdown(true);
            spec.setAutoDataRead(false);
            services = HidManager.getHidServices(spec);
        }
        return services;
    }

    @Override
    public List<HidDeviceRef> enumerate(int vendorId) {
        List<HidDeviceRef> out = new ArrayList<>();
        try {
            for (HidDevice d : services().getAttachedHidDevices()) {
                if ((d.getVendorId() & 0xFFFF) != (vendorId & 0xFFFF)) continue;

                out.add(new HidDeviceRef(
                        d.getPath(),
                        d.getVendorId() & 0xFFFF,
                        d.getProductId() & 0xFFFF,
                        normalizeSerial(d),
                        d.getUsagePage() & 0xFFFF,
                        d.getUsage() & 0xFFFF));
            }
        } catch (Exception e) {
            throw new HidException("HID enumeration failed", e);
        }
        return out;
    }

    private static @Nullable String normalizeSerial(HidDevice device) {
        String serial = emptyToNull(device.getSerialNumber());

        if (serial == null || serial.isBlank() || serial.trim().equalsIgnoreCase("Invalid SN!")) {
            return null;
        }

        return serial.trim();
    }

    @Override
    public HidDeviceHandle open(HidDeviceRef ref) {
        HidDevice device = null;
        for (HidDevice d : services().getAttachedHidDevices()) {
            if (ref.path().equals(d.getPath())) { device = d; break; }
        }
        if (device == null) throw new HidException("device vanished before it could be opened: " + ref.path());
        if (device.isClosed() && !device.open()) {
            throw new HidException("could not open " + ref.path()
                    + " (in use by the Elgato software, or missing permissions?)");
        }
        device.setNonBlocking(false);
        LOGGER.debug("Opened HID device {} (vid {}, pid {})",
                ref.path(), Integer.toHexString(ref.vendorId()), Integer.toHexString(ref.productId()));
        return new Handle(device);
    }

    @Override
    public synchronized void close() {
        if (services != null) {
            try { services.shutdown(); } catch (Exception ignored) { }
            services = null;
        }
    }

    private static @Nullable String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private record Handle(HidDevice device) implements HidDeviceHandle {
        @Override
        public void write(byte[] report) {
            byte[] payload = new byte[report.length - 1];
            System.arraycopy(report, 1, payload, 0, payload.length);
            int written = device.write(payload, payload.length, report[0]);
            if (written < 0) throw new HidException("HID write failed: " + device.getLastErrorMessage());
        }

        @Override
        public void sendFeatureReport(byte[] report) {
            byte[] payload = new byte[report.length - 1];
            System.arraycopy(report, 1, payload, 0, payload.length);
            int written = device.sendFeatureReport(payload, report[0]);
            if (written < 0) throw new HidException("feature report failed: " + device.getLastErrorMessage());
        }

        @Override
        public byte[] getFeatureReport(int reportId, int length) {
            // Hid4Java strips the leading report id from what it copies back, so ask for one
            // byte less and put the id back ourselves. Callers always see a full report.
            byte[] payload = new byte[Math.max(0, length - 1)];
            int read = device.getFeatureReport(payload, (byte) reportId);
            if (read < 0) throw new HidException("feature report read failed: " + device.getLastErrorMessage());
            byte[] full = new byte[length];
            full[0] = (byte) reportId;
            System.arraycopy(payload, 0, full, 1, payload.length);
            return full;
        }

        @Override
        public int read(byte[] buffer, int timeoutMs) {
            return device.read(buffer, timeoutMs);
        }

        @Override
        public void close() {
            try { device.close(); } catch (Exception ignored) { }
        }
    }
}