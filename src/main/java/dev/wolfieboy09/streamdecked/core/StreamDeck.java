package dev.wolfieboy09.streamdecked.core;

import dev.wolfieboy09.streamdecked.core.hid.HidBackend;
import dev.wolfieboy09.streamdecked.core.image.DeckImage;
import dev.wolfieboy09.streamdecked.core.image.DeckImageCodec;

import javax.annotation.concurrent.NotThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A single open Stream Deck.
 *
 * <p>Low level and synchronous: every call here turns into HID traffic on the calling thread.
 * In a game you almost certainly want {@link StreamDeckManager} instead, which owns a driver
 * thread and keeps this object off the render thread.</p>
 *
 * <p>Not thread safe.</p>
 */
@SuppressWarnings("unused")
@NotThreadSafe
public final class StreamDeck implements AutoCloseable {
    private final DeckModel model;
    private final HidBackend.HidDeviceHandle hid;
    private final String id;
    private final byte[] readBuffer;
    private boolean closed;

    public StreamDeck(DeckModel model, HidBackend.HidDeviceHandle hid, String id) {
        this.model = model;
        this.hid = hid;
        this.id = id;
        this.readBuffer = new byte[inputReportLength(model)];
    }

    /** Stable identifier, the USB serial where the OS exposes one. */
    public String id()       { return id; }
    public DeckModel model() { return model; }
    public boolean isClosed(){ return closed; }

    // ------------------------------------------------------------------
    // Device control
    // ------------------------------------------------------------------

    /** Clears every screen and returns the device to its idle logo. */
    public void reset() {
        byte[] r;
        if (model.generation() == DeckModel.Gen.V1) {
            r = new byte[17];
            r[0] = 0x0B; r[1] = 0x63;
        } else {
            r = new byte[32];
            r[0] = 0x03; r[1] = 0x02;
        }
        hid.sendFeatureReport(r);
    }

    /** @param percent 0 to 100 */
    public void setBrightness(int percent) {
        int p = Math.clamp(percent, 0, 100);
        byte[] r;
        if (model.generation() == DeckModel.Gen.V1) {
            r = new byte[17];
            r[0] = 0x05; r[1] = 0x55; r[2] = (byte) 0xAA; r[3] = (byte) 0xD1; r[4] = 0x01;
            r[5] = (byte) p;
        } else {
            r = new byte[32];
            r[0] = 0x03; r[1] = 0x08; r[2] = (byte) p;
        }
        hid.sendFeatureReport(r);
    }

    public String serialNumber() {
        return switch (model) {
            case ORIGINAL, MINI -> ascii(hid.getFeatureReport(0x03, 17), 5);
            case MINI_MK2, MINI_DISCORD, MINI_MK2_MODULE -> ascii(hid.getFeatureReport(0x03, 32), 5);
            default -> ascii(hid.getFeatureReport(0x06, 32), 2);
        };
    }

    public String firmwareVersion() {
        return switch (model) {
            case ORIGINAL, MINI, MINI_MK2, MINI_DISCORD -> ascii(hid.getFeatureReport(0x04, 17), 5);
            case MINI_MK2_MODULE -> ascii(hid.getFeatureReport(0xA1, 17), 5);
            default -> ascii(hid.getFeatureReport(0x05, 32), 6);
        };
    }

    /** Sets the color of a Neo capacitive touch point's LED. */
    public void setTouchPointColor(int point, int red, int green, int blue) {
        if (point < 0 || point >= model.touchpointCount()) {
            throw new IllegalArgumentException(model + " has no touch point " + point);
        }
        byte[] r = new byte[] {
                0x03, 0x06, (byte) (model.keyCount() + point),
                (byte) red, (byte) green, (byte) blue
        };
        hid.sendFeatureReport(r);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** Scales, reorients, encodes and uploads an image to one key. */
    public void setKeyImage(int key, DeckImage image) {
        setKeyImageEncoded(key, DeckImageCodec.encodeKey(model, image));
    }

    /**
     * Uploads bytes already produced by {@link DeckImageCodec#encodeKey}. Worth using when the
     * same image goes to several keys or is redrawn often: encoding is the expensive half.
     */
    public void setKeyImageEncoded(int key, byte[] encoded) {
        if (!model.hasKeyScreens()) throw new UnsupportedOperationException(model + " has no key screens");
        if (key < 0 || key >= model.keyCount()) throw new IllegalArgumentException("no key " + key);

        final int wireKey = model.toWireKeyIndex(key);
        final int reportLength = model.imageReportLength();
        final int payloadLength = model.imageReportPayloadLength(encoded.length);

        writeImageReports(encoded, reportLength, payloadLength, (page, length, last) -> {
            if (model.generation() == DeckModel.Gen.V1) {
                // The original panel numbers its two pages from 1; the minis from 0.
                int pageValue = (model == DeckModel.ORIGINAL) ? page + 1 : page;
                byte[] h = new byte[16];
                h[0] = 0x02; h[1] = 0x01;
                h[2] = (byte) pageValue;
                h[3] = 0x00;
                h[4] = (byte) (last ? 1 : 0);
                h[5] = (byte) (wireKey + 1);
                return h;
            }
            return new byte[] {
                    0x02, 0x07, (byte) wireKey, (byte) (last ? 1 : 0),
                    (byte) (length & 0xFF), (byte) ((length >> 8) & 0xFF),
                    (byte) (page & 0xFF), (byte) ((page >> 8) & 0xFF)
            };
        });
    }

    public void clearKey(int key) {
        setKeyImageEncoded(key, DeckImageCodec.blankKey(model));
    }

    public void clearAllKeys() {
        if (!model.hasKeyScreens()) return;
        byte[] blank = DeckImageCodec.blankKey(model);
        for (int i = 0; i < model.keyCount(); i++) setKeyImageEncoded(i, blank);
    }

    /** Fills the whole LCD strip / info screen. */
    public void setScreenImage(DeckImage image) {
        setScreenImageEncoded(DeckImageCodec.encodeScreen(model, image));
    }

    public void setScreenImageEncoded(byte[] encoded) {
        DeckModel.ImageSpec spec = model.screenImage();
        if (spec == null) throw new UnsupportedOperationException(model + " has no screen");

        if (model.hasPartialScreenWrites()) {
            writeScreenRegion(0, 0, spec.width(), spec.height(), encoded);
        } else {
            // Neo style: same 8 byte header shape as keys, command 0x0b.
            writeImageReports(encoded, 1024, 1024 - 8, (page, length, last) -> new byte[] {
                    0x02, 0x0B, 0x00, (byte) (last ? 1 : 0),
                    (byte) (length & 0xFF), (byte) ((length >> 8) & 0xFF),
                    (byte) (page & 0xFF), (byte) ((page >> 8) & 0xFF)
            });
        }
    }

    /**
     * Draws into part of the touch strip, leaving the rest alone. Stream Deck + only; on a Neo
     * redraw the whole screen instead.
     */
    public void setScreenRegion(int x, int y, DeckImage image) {
        if (!model.hasPartialScreenWrites()) {
            throw new UnsupportedOperationException(model + " cannot do partial screen writes");
        }
        writeScreenRegion(x, y, image.width(), image.height(),
                DeckImageCodec.encodeScreenRegion(model, image));
    }

    private void writeScreenRegion(int x, int y, int w, int h, byte[] encoded) {
        writeImageReports(encoded, 1024, 1024 - 16, (page, length, last) -> new byte[] {
                0x02, 0x0C,
                (byte) (x & 0xFF), (byte) ((x >> 8) & 0xFF),
                (byte) (y & 0xFF), (byte) ((y >> 8) & 0xFF),
                (byte) (w & 0xFF), (byte) ((w >> 8) & 0xFF),
                (byte) (h & 0xFF), (byte) ((h >> 8) & 0xFF),
                (byte) (last ? 1 : 0),
                (byte) (page & 0xFF), (byte) ((page >> 8) & 0xFF),
                (byte) (length & 0xFF), (byte) ((length >> 8) & 0xFF),
                0x00
        });
    }

    private interface HeaderBuilder {
        byte[] build(int page, int payloadLength, boolean last);
    }

    private void writeImageReports(byte[] data, int reportLength, int payloadLength, HeaderBuilder header) {
        int page = 0;
        int remaining = data.length;
        while (remaining > 0) {
            int length = Math.min(remaining, payloadLength);
            int offset = page * payloadLength;
            boolean last = length == remaining;

            byte[] report = new byte[reportLength];
            byte[] h = header.build(page, length, last);
            System.arraycopy(h, 0, report, 0, h.length);
            System.arraycopy(data, offset, report, h.length, length);
            // The rest of the report stays zero: the firmware wants full length reports.
            hid.write(report);

            remaining -= length;
            page++;
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Reads one input report.
     *
     * @param timeoutMs how long to block; 0 polls
     * @return {@link DeckInput#NONE} if nothing arrived
     */
    public DeckInput read(int timeoutMs) {
        Arrays.fill(readBuffer, (byte) 0);
        int read = hid.read(readBuffer, timeoutMs);
        if (read <= 0 || readBuffer[0] == 0) return DeckInput.NONE;

        if (model == DeckModel.PLUS || model == DeckModel.PLUS_XL) {
            return switch (readBuffer[1]) {
                case 0x00 -> new DeckInput.Buttons(readButtons(readBuffer));
                case 0x02 -> readTouch(readBuffer);
                case 0x03 -> readEncoder(readBuffer);
                default -> DeckInput.NONE;
            };
        }
        return new DeckInput.Buttons(readButtons(readBuffer));
    }

    private boolean[] readButtons(byte[] data) {
        int count = model.buttonStateCount();
        int offset = (model.generation() == DeckModel.Gen.V1) ? 1 : 4;
        boolean[] states = new boolean[count];
        for (int i = 0; i < count; i++) {
            int wire = (i < model.keyCount()) ? model.toWireKeyIndex(i) : i;
            int idx = offset + wire;
            states[i] = idx < data.length && data[idx] != 0;
        }
        return states;
    }

    private DeckInput readTouch(byte[] data) {
        int startX = u16(data, 6);
        int startY = u16(data, 8);
        return switch (data[4]) {
            case 0x01 -> new DeckInput.TouchTap(startX, startY);
            case 0x02 -> new DeckInput.TouchHold(startX, startY);
            case 0x03 -> new DeckInput.TouchSwipe(startX, startY, u16(data, 10), u16(data, 12));
            default -> DeckInput.NONE;
        };
    }

    private DeckInput readEncoder(byte[] data) {
        int n = model.encoderCount();
        if (data[4] == 0x00) {
            boolean[] states = new boolean[n];
            for (int i = 0; i < n; i++) states[i] = data[5 + i] != 0;
            return new DeckInput.EncoderPress(states);
        }
        if (data[4] == 0x01) {
            int[] deltas = new int[n];
            for (int i = 0; i < n; i++) deltas[i] = data[5 + i]; // already signed
            return new DeckInput.EncoderTurn(deltas);
        }
        return DeckInput.NONE;
    }

    private static int inputReportLength(DeckModel model) {
        if (model == DeckModel.PLUS || model == DeckModel.PLUS_XL) {
            return Math.max(6 + model.keyCount(), Math.max(14, 5 + model.encoderCount()));
        }
        if (model.generation() == DeckModel.Gen.V1) return 1 + model.keyCount();
        return 4 + model.buttonStateCount();
    }

    private static int u16(byte[] data, int off) {
        if (off + 1 >= data.length) return 0;
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }

    private static String ascii(byte[] report, int offset) {
        int end = offset;
        while (end < report.length && report[end] != 0) end++;
        return new String(report, offset, end - offset, StandardCharsets.US_ASCII)
                .replace("\u0001", "").trim();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        hid.close();
    }
}