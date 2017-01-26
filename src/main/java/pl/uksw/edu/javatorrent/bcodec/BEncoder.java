package pl.uksw.edu.javatorrent.bcodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
public class BEncoder {

    @SuppressWarnings("unchecked")
    public static void bencode(Object o, OutputStream out)
            throws IOException, IllegalArgumentException {
        if (o instanceof BEValue) {
            o = ((BEValue)o).getValue();
        }

        if (o instanceof String) {
            bencode((String)o, out);
        } else if (o instanceof byte[]) {
            bencode((byte[])o, out);
        } else if (o instanceof Number) {
            bencode((Number)o, out);
        } else if (o instanceof List) {
            bencode((List<BEValue>)o, out);
        } else if (o instanceof Map) {
            bencode((Map<String, BEValue>)o, out);
        } else {
            throw new IllegalArgumentException("Cannot bencode: " +
                    o.getClass());
        }
    }

    public static void bencode(String s, OutputStream out) throws IOException {
        byte[] bs = s.getBytes("UTF-8");
        bencode(bs, out);
    }

    public static void bencode(Number n, OutputStream out) throws IOException {
        out.write('i');
        String s = n.toString();
        out.write(s.getBytes("UTF-8"));
        out.write('e');
    }

    public static void bencode(List<BEValue> l, OutputStream out)
            throws IOException {
        out.write('l');
        for (BEValue value : l) {
            bencode(value, out);
        }
        out.write('e');
    }

    public static void bencode(byte[] bs, OutputStream out) throws IOException {
        String l = Integer.toString(bs.length);
        out.write(l.getBytes("UTF-8"));
        out.write(':');
        out.write(bs);
    }

    public static void bencode(Map<String, BEValue> m, OutputStream out)
            throws IOException {
        out.write('d');
        Set<String> s = m.keySet();
        List<String> l = new ArrayList<String>(s);
        Collections.sort(l);

        for (String key : l) {
            Object value = m.get(key);
            bencode(key, out);
            bencode(value, out);
        }

        out.write('e');
    }

    public static ByteBuffer bencode(Map<String, BEValue> m)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(m, baos);
        baos.close();
        return ByteBuffer.wrap(baos.toByteArray());
    }
}
