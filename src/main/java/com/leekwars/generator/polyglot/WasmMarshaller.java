package com.leekwars.generator.polyglot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire codec for the Rust/WASM host boundary — the Java mirror of the guest-side
 * codec in {@code sdk/rust/leekwars/src/codec.rs}. Both sides MUST agree
 * byte-for-byte (cross-checked in {@code TestWasmMarshaller}).
 *
 * <p>Scope: this class is the dependency-free WIRE half — it turns
 * {@link LwValue} trees into bytes and back. The RUNTIME half (converting between
 * {@code LwValue} and GraalVM {@code Value}/the game objects, and resolving the
 * called function like {@link PolyglotAPIBridge}) lives in {@code WasmAPIBridge},
 * added with the GraalWasm host integration. Keeping the codec standalone lets it
 * be unit-tested without a running GraalVM.
 *
 * <p>Type set mirrors {@code TypeMarshaller} / LeekScript: null, int (i64), double,
 * string (utf-8), list, map. Encoding is little-endian and deterministic (maps are
 * emitted in insertion order; canonicalising doubles/NaN is the caller's job).
 *
 * <pre>
 * value    := tag:u8 payload
 *   0x00 NULL    ()          0x04 STRING  len:u32 utf8[len]
 *   0x01 BOOL    u8          0x05 LIST    count:u32 value*count
 *   0x02 INT     i64         0x06 MAP     count:u32 (key,value)*count
 *   0x03 FLOAT   f64
 * request  := name_len:u32 name_utf8 argc:u32 value*argc
 * response := status:u8 (0=ok,1=err) value
 * </pre>
 */
public final class WasmMarshaller {

    private WasmMarshaller() {}

    static final int TAG_NULL = 0x00;
    static final int TAG_BOOL = 0x01;
    static final int TAG_INT = 0x02;
    static final int TAG_FLOAT = 0x03;
    static final int TAG_STRING = 0x04;
    static final int TAG_LIST = 0x05;
    static final int TAG_MAP = 0x06;

    // ---------- value model ----------

    /** A boundary value. Mirrors the Rust {@code Value} enum. */
    public sealed interface LwValue
            permits LwNull, LwBool, LwInt, LwFloat, LwStr, LwList, LwMap {}

    public record LwNull() implements LwValue {
        public static final LwNull INSTANCE = new LwNull();
    }
    public record LwBool(boolean value) implements LwValue {}
    public record LwInt(long value) implements LwValue {}
    public record LwFloat(double value) implements LwValue {}
    public record LwStr(String value) implements LwValue {}
    public record LwList(List<LwValue> items) implements LwValue {}
    /** Ordered key/value pairs (insertion order = wire order, for determinism). */
    public record LwMap(List<Entry> entries) implements LwValue {
        public record Entry(LwValue key, LwValue value) {}
    }

    /** A decoded {@code lw_call} request. */
    public record Request(String name, List<LwValue> args) {}

    // ---------- encoding ----------

    /** Growable little-endian byte sink. */
    private static final class Sink {
        private byte[] buf = new byte[64];
        private int len = 0;

        void u8(int b) {
            ensure(1);
            buf[len++] = (byte) b;
        }
        void u32(int v) {
            ensure(4);
            buf[len++] = (byte) v;
            buf[len++] = (byte) (v >>> 8);
            buf[len++] = (byte) (v >>> 16);
            buf[len++] = (byte) (v >>> 24);
        }
        void i64(long v) {
            ensure(8);
            for (int i = 0; i < 8; i++) {
                buf[len++] = (byte) (v >>> (8 * i));
            }
        }
        void bytes(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, buf, len, b.length);
            len += b.length;
        }
        private void ensure(int n) {
            if (len + n > buf.length) {
                int cap = buf.length;
                while (cap < len + n) cap <<= 1;
                byte[] grown = new byte[cap];
                System.arraycopy(buf, 0, grown, 0, len);
                buf = grown;
            }
        }
        byte[] toArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }

    private static void writeValue(Sink s, LwValue v) {
        if (v instanceof LwNull) {
            s.u8(TAG_NULL);
        } else if (v instanceof LwBool b) {
            s.u8(TAG_BOOL);
            s.u8(b.value() ? 1 : 0);
        } else if (v instanceof LwInt i) {
            s.u8(TAG_INT);
            s.i64(i.value());
        } else if (v instanceof LwFloat f) {
            s.u8(TAG_FLOAT);
            s.i64(Double.doubleToLongBits(f.value()));
        } else if (v instanceof LwStr str) {
            s.u8(TAG_STRING);
            byte[] utf8 = str.value().getBytes(StandardCharsets.UTF_8);
            s.u32(utf8.length);
            s.bytes(utf8);
        } else if (v instanceof LwList list) {
            s.u8(TAG_LIST);
            s.u32(list.items().size());
            for (LwValue it : list.items()) {
                writeValue(s, it);
            }
        } else if (v instanceof LwMap map) {
            s.u8(TAG_MAP);
            s.u32(map.entries().size());
            for (LwMap.Entry e : map.entries()) {
                writeValue(s, e.key());
                writeValue(s, e.value());
            }
        }
    }

    /** Encode a successful response ({@code status=0} + value). */
    public static byte[] encodeResponse(LwValue value) {
        Sink s = new Sink();
        s.u8(0);
        writeValue(s, value);
        return s.toArray();
    }

    /** Encode an error response ({@code status=1} + message string). */
    public static byte[] encodeError(String message) {
        Sink s = new Sink();
        s.u8(1);
        writeValue(s, new LwStr(message == null ? "" : message));
        return s.toArray();
    }

    /** Encode a request (used by tests and by the guest-side mirror). */
    public static byte[] encodeRequest(String name, List<LwValue> args) {
        Sink s = new Sink();
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        s.u32(utf8.length);
        s.bytes(utf8);
        s.u32(args.size());
        for (LwValue a : args) {
            writeValue(s, a);
        }
        return s.toArray();
    }

    // ---------- decoding ----------

    /** Forward-only little-endian cursor; underflow yields NULL (see codec.rs). */
    private static final class Cursor {
        private final byte[] buf;
        private int pos;

        Cursor(byte[] buf) {
            this.buf = buf;
        }
        private boolean has(int n) {
            return pos + n <= buf.length;
        }
        int u8() {
            return has(1) ? (buf[pos++] & 0xFF) : -1;
        }
        int u32() {
            if (!has(4)) {
                pos = buf.length;
                return 0;
            }
            int v = (buf[pos] & 0xFF)
                    | (buf[pos + 1] & 0xFF) << 8
                    | (buf[pos + 2] & 0xFF) << 16
                    | (buf[pos + 3] & 0xFF) << 24;
            pos += 4;
            return v;
        }
        long i64() {
            if (!has(8)) {
                pos = buf.length;
                return 0L;
            }
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v |= (long) (buf[pos + i] & 0xFF) << (8 * i);
            }
            pos += 8;
            return v;
        }
        byte[] take(int n) {
            if (n < 0 || !has(n)) {
                pos = buf.length;
                return new byte[0];
            }
            byte[] out = new byte[n];
            System.arraycopy(buf, pos, out, 0, n);
            pos += n;
            return out;
        }
        LwValue value() {
            int tag = u8();
            switch (tag) {
                case TAG_NULL:
                    return LwNull.INSTANCE;
                case TAG_BOOL:
                    return new LwBool(u8() != 0);
                case TAG_INT:
                    return new LwInt(i64());
                case TAG_FLOAT:
                    return new LwFloat(Double.longBitsToDouble(i64()));
                case TAG_STRING:
                    return new LwStr(new String(take(u32()), StandardCharsets.UTF_8));
                case TAG_LIST: {
                    int count = clampCount(u32());
                    List<LwValue> items = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        items.add(value());
                    }
                    return new LwList(items);
                }
                case TAG_MAP: {
                    int count = clampCount(u32());
                    List<LwMap.Entry> entries = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        LwValue k = value();
                        LwValue v = value();
                        entries.add(new LwMap.Entry(k, v));
                    }
                    return new LwMap(entries);
                }
                default:
                    return LwNull.INSTANCE;
            }
        }
    }

    /** Guard against a corrupt huge count pre-allocating memory. */
    private static int clampCount(int count) {
        return Math.max(0, Math.min(count, 1 << 24));
    }

    /** Decode a {@code lw_call} request from guest memory. */
    public static Request decodeRequest(byte[] buf) {
        Cursor c = new Cursor(buf);
        String name = new String(c.take(c.u32()), StandardCharsets.UTF_8);
        int argc = clampCount(c.u32());
        List<LwValue> args = new ArrayList<>(argc);
        for (int i = 0; i < argc; i++) {
            args.add(c.value());
        }
        return new Request(name, args);
    }

    /** Decode a single value (e.g. a response body already past the status byte). */
    public static LwValue decodeValue(byte[] buf) {
        return new Cursor(buf).value();
    }
}
