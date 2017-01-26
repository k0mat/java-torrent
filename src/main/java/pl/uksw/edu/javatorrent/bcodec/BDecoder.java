package pl.uksw.edu.javatorrent.bcodec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.EOFException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.io.input.AutoCloseInputStream;

public class BDecoder {
    private final InputStream in;
    private int indicator = 0;
    public BDecoder(InputStream in) {
        this.in = in;
    }

    public static BEValue bdecode(InputStream in) throws IOException {
        return new BDecoder(in).bdecode();
    }
    public static BEValue bdecode(ByteBuffer data) throws IOException {
        return BDecoder.bdecode(new AutoCloseInputStream(
                new ByteArrayInputStream(data.array())));
    }
    private int getNextIndicator() throws IOException {
        if (this.indicator == 0) {
            this.indicator = in.read();
        }
        return this.indicator;
    }

    public BEValue bdecode() throws IOException	{
        if (this.getNextIndicator() == -1)
            return null;

        if (this.indicator >= '0' && this.indicator <= '9')
            return this.bdecodeBytes();
        else if (this.indicator == 'i')
            return this.bdecodeNumber();
        else if (this.indicator == 'l')
            return this.bdecodeList();
        else if (this.indicator == 'd')
            return this.bdecodeMap();
        else
            throw new InvalidBEncodingException
                    ("Unknown indicator '" + this.indicator + "'");
    }


    public BEValue bdecodeBytes() throws IOException {
        int c = this.getNextIndicator();
        int num = c - '0';
        if (num < 0 || num > 9)
            throw new InvalidBEncodingException("Number expected, not '"
                    + (char)c + "'");
        this.indicator = 0;

        c = this.read();
        int i = c - '0';
        while (i >= 0 && i <= 9) {
            num = num*10 + i;
            c = this.read();
            i = c - '0';
        }

        if (c != ':') {
            throw new InvalidBEncodingException("Colon expected, not '" +
                    (char)c + "'");
        }

        return new BEValue(read(num));
    }
    public BEValue bdecodeNumber() throws IOException {
        int c = this.getNextIndicator();
        if (c != 'i') {
            throw new InvalidBEncodingException("Expected 'i', not '" +
                    (char)c + "'");
        }
        this.indicator = 0;

        c = this.read();
        if (c == '0') {
            c = this.read();
            if (c == 'e')
                return new BEValue(BigInteger.ZERO);
            else
                throw new InvalidBEncodingException("'e' expected after zero," +
                        " not '" + (char)c + "'");
        }

        char[] chars = new char[256];
        int off = 0;

        if (c == '-') {
            c = this.read();
            if (c == '0')
                throw new InvalidBEncodingException("Negative zero not allowed");
            chars[off] = '-';
            off++;
        }

        if (c < '1' || c > '9')
            throw new InvalidBEncodingException("Invalid Integer start '"
                    + (char)c + "'");
        chars[off] = (char)c;
        off++;

        c = this.read();
        int i = c - '0';
        while (i >= 0 && i <= 9) {
            chars[off] = (char)c;
            off++;
            c = read();
            i = c - '0';
        }

        if (c != 'e')
            throw new InvalidBEncodingException("Integer should end with 'e'");

        String s = new String(chars, 0, off);
        return new BEValue(new BigInteger(s));
    }

    public BEValue bdecodeList() throws IOException {
        int c = this.getNextIndicator();
        if (c != 'l') {
            throw new InvalidBEncodingException("Expected 'l', not '" +
                    (char)c + "'");
        }
        this.indicator = 0;

        List<BEValue> result = new ArrayList<BEValue>();
        c = this.getNextIndicator();
        while (c != 'e') {
            result.add(this.bdecode());
            c = this.getNextIndicator();
        }
        this.indicator = 0;

        return new BEValue(result);
    }

    public BEValue bdecodeMap() throws IOException {
        int c = this.getNextIndicator();
        if (c != 'd') {
            throw new InvalidBEncodingException("Expected 'd', not '" +
                    (char)c + "'");
        }
        this.indicator = 0;

        Map<String, BEValue> result = new HashMap<String, BEValue>();
        c = this.getNextIndicator();
        while (c != 'e') {
            String key = this.bdecode().getString();

            BEValue value = this.bdecode();
            result.put(key, value);

            c = this.getNextIndicator();
        }
        this.indicator = 0;

        return new BEValue(result);
    }
    private int read() throws IOException {
        int c = this.in.read();
        if (c == -1)
            throw new EOFException();
        return c;
    }

    private byte[] read(int length) throws IOException {
        byte[] result = new byte[length];

        int read = 0;
        while (read < length)
        {
            int i = this.in.read(result, read, length - read);
            if (i == -1)
                throw new EOFException();
            read += i;
        }

        return result;
    }
}
