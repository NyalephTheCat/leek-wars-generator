package test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.leekwars.generator.polyglot.WasmMarshaller;
import com.leekwars.generator.polyglot.WasmMarshaller.LwBool;
import com.leekwars.generator.polyglot.WasmMarshaller.LwFloat;
import com.leekwars.generator.polyglot.WasmMarshaller.LwInt;
import com.leekwars.generator.polyglot.WasmMarshaller.LwList;
import com.leekwars.generator.polyglot.WasmMarshaller.LwMap;
import com.leekwars.generator.polyglot.WasmMarshaller.LwNull;
import com.leekwars.generator.polyglot.WasmMarshaller.LwStr;
import com.leekwars.generator.polyglot.WasmMarshaller.LwValue;
import com.leekwars.generator.polyglot.WasmMarshaller.Request;

/**
 * Guards that the Java wire codec ({@link WasmMarshaller}) stays byte-for-byte
 * compatible with the Rust guest codec ({@code sdk/rust/leekwars/src/codec.rs}).
 *
 * <p>The two golden hex strings were produced by the Rust {@code encode_request}
 * on the same inputs; if either side drifts, this test fails. (Cross-verified
 * standalone against the Rust output during development.)
 */
public class TestWasmMarshaller {

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /** Two ints and a string — the common combat-call shape. */
    @Test
    public void requestMatchesRustGolden() {
        byte[] expected = hex(
                "07000000757365436869700300000002250000000000000002d20000000000000004020000006869");
        byte[] actual = WasmMarshaller.encodeRequest(
                "useChip", List.of(new LwInt(37), new LwInt(210), new LwStr("hi")));
        assertArrayEquals(expected, actual);
    }

    /** Nested list/map/null/bool/float — exercises every tag. */
    @Test
    public void nestedRequestMatchesRustGolden() {
        byte[] expected = hex(
                "0100000078010000000504000000020100000000000000060100000004010000006b030000000000000440000101");
        LwValue nested = new LwList(List.of(
                new LwInt(1),
                new LwMap(List.of(new LwMap.Entry(new LwStr("k"), new LwFloat(2.5)))),
                LwNull.INSTANCE,
                new LwBool(true)));
        assertArrayEquals(expected, WasmMarshaller.encodeRequest("x", List.of(nested)));
    }

    @Test
    public void requestDecodeRoundTrips() {
        LwValue nested = new LwList(List.of(
                new LwInt(1),
                new LwMap(List.of(new LwMap.Entry(new LwStr("k"), new LwFloat(2.5)))),
                LwNull.INSTANCE,
                new LwBool(true)));
        byte[] wire = WasmMarshaller.encodeRequest("x", List.of(nested));
        Request req = WasmMarshaller.decodeRequest(wire);
        assertEquals("x", req.name());
        assertArrayEquals(wire, WasmMarshaller.encodeRequest(req.name(), req.args()));
    }

    @Test
    public void responseValueRoundTrips() {
        byte[] ok = WasmMarshaller.encodeResponse(new LwInt(42));
        LwValue v = WasmMarshaller.decodeValue(Arrays.copyOfRange(ok, 1, ok.length));
        assertEquals(new LwInt(42), v);
    }

    /** Truncated input degrades to NULL rather than throwing (see codec.rs). */
    @Test
    public void truncatedDecodeIsGraceful() {
        assertEquals(LwNull.INSTANCE, WasmMarshaller.decodeValue(new byte[0]));
        assertEquals(new LwInt(0), WasmMarshaller.decodeValue(new byte[] { 0x02, 0x01 }));
    }
}
