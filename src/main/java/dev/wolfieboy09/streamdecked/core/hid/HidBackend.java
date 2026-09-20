package dev.wolfieboy09.streamdecked.core.hid;

import java.util.List;

/**
 * Everything the Stream Deck driver needs from a HID library.
 *
 * <p>Implemented by {@link Hid4JavaBackend}. Swap in your own if you would rather not ship
 * Hid4Java, or supply a fake one in tests.</p>
 *
 * <p>All methods are called from the driver thread only.</p>
 */
public interface HidBackend extends AutoCloseable {
    /** Every currently attached interface for the given vendor. */
    List<HidDeviceRef> enumerate(int vendorId);

    /** Opens a device for exclusive use. Throws {@link HidException} if it cannot be claimed. */
    HidDeviceHandle open(HidDeviceRef ref);

    @Override
    void close();

    /**
     * One attached HID interface.
     *
     * @param path            OS level identifier, stable while the device stays plugged in
     * @param vendorId        USB vendor id
     * @param productId       USB product id
     * @param serialNumber    device serial, or null if the OS would not hand it over
     *                        (on Linux this usually means missing udev permissions)
     * @param usagePage       HID usage page, used to skip the non-control interfaces some
     *                        models expose
     * @param usage           HID usage
     */
    record HidDeviceRef(String path, int vendorId, int productId, String serialNumber,
                        int usagePage, int usage) {

        /** Best available stable identity for a device. */
        public String identity() {
            if (serialNumber == null) {
                return path;
            }

            String serial = serialNumber.trim();

            return serial.isEmpty() || serial.equalsIgnoreCase("Invalid SN!")
                    ? path
                    : serial;
        }
    }

    /** An opened device. Not thread safe; the driver keeps all access on one thread. */
    interface HidDeviceHandle extends AutoCloseable {

        /**
         * Writes an output report. {@code report[0]} must be the report id.
         * Implementations are responsible for whatever prefixing their native layer wants.
         */
        void write(byte[] report);

        /** Sends a feature report. {@code report[0]} must be the report id. */
        void sendFeatureReport(byte[] report);

        /**
         * Reads a feature report.
         *
         * @param reportId report id to request
         * @param length   total length to read, including the leading report id byte
         * @return a buffer of {@code length} bytes whose index 0 is the report id
         */
        byte[] getFeatureReport(int reportId, int length);

        /**
         * Reads an input report.
         *
         * @param buffer    destination; index 0 receives the report id
         * @param timeoutMs how long to block, 0 for non-blocking
         * @return number of bytes read, 0 on timeout, negative on error
         */
        int read(byte[] buffer, int timeoutMs);

        @Override
        void close();
    }

    /** Anything the underlying HID layer refuses to do. */
    class HidException extends RuntimeException {
        public HidException(String message) { super(message); }
        public HidException(String message, Throwable cause) { super(message, cause); }
    }
}